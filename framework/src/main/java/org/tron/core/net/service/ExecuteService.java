package org.tron.core.net.service;


import com.google.protobuf.ByteString;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.tron.core.net.messagehandler.ExecuteLog;
import org.tron.core.net.messagehandler.MyLogger;
import org.tron.core.net.messagehandler.TransactionExtension;
import org.tron.core.net.messagehandler.TransanctionType;
import org.tron.trident.abi.FunctionEncoder;
import org.tron.trident.abi.FunctionReturnDecoder;
import org.tron.trident.abi.TypeReference;
import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.DynamicArray;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.abi.datatypes.Type;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Chain.Transaction;
import org.tron.trident.proto.Contract;
import org.tron.trident.proto.Response.TransactionExtention;
import org.tron.trident.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.tron.core.net.service.Constant.*;
import static org.tron.trident.abi.Utils.typeMap;
import static org.tron.trident.core.ApiWrapper.parseAddress;

@Service
public class ExecuteService {

	private final ByteString bsSunSwapRouterAddress = parseAddress(Constant.sContractSunSwapRouterAddress);
	private final OkHttpClient okHttpClient = new OkHttpClient();
	private String PK = null;
	private ApiWrapper apiWrapper = null;
	public String sWalletAddressT = null;
	private String sWalletHexAddress = null;
	private ByteString bsWalletAddress = null;
	private Address addressWallet = null;
	private long trx_min = 0;
	private long trx_max = 0;
	private int count1_min = 0;
	private int count1_max = 0;
	private int count2_min = 0;
	private int count2_max = 0;
	private Map<String, String[]> sUrls = new HashMap<>();

	private static ExecuteService _instance = null;

	public static ExecuteService getInstance() {
		if (_instance == null) {
			_instance = new ExecuteService();
		}

		return _instance;
	}

	public void notifyEnvChange() {
		EnvService envService = EnvService.getInstance();
		String newPK = envService.get("PK");
		if (PK == null || !PK.equals(newPK)) {
			if (apiWrapper != null) {
				apiWrapper.close();
			}

			PK = newPK;
			apiWrapper = new ApiWrapper(sUrlGrpcEndpoint, sUrlGrpcSolidityEndpoint, PK);
			sWalletAddressT = apiWrapper.keyPair.toBase58CheckAddress();
			sWalletHexAddress = apiWrapper.keyPair.toHexAddress();
			bsWalletAddress = parseAddress(sWalletHexAddress);
			addressWallet = new Address(sWalletHexAddress);
		}

		trx_min = Long.parseLong(envService.get("TRXMINAMOUNT"));
		trx_max = Long.parseLong(envService.get("TRXMAXAMOUNT"));
		count1_min = Integer.parseInt(envService.get("COUNT1MIN"));
		count1_max = Integer.parseInt(envService.get("COUNT1MAX"));
		count2_min = Integer.parseInt(envService.get("COUNT2MIN"));
		count2_max = Integer.parseInt(envService.get("COUNT2MAX"));
	}

	public void clearApiList() {
		sUrls.clear();
	}

	public void updateApi(String key, String[] value) {
		sUrls.put(key, value);
	}

	private Uint256 getOutputExpectation(List<String> lstPath, Uint256 lAmountIn) {
		Function funcGetAmountsOut = new Function("getAmountsOut", Arrays.asList(lAmountIn,
				new DynamicArray<>(Address.class, typeMap(lstPath, Address.class))),
				Collections.singletonList(new TypeReference<DynamicArray<Uint256>>() {
				}));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, Constant.sContractSunSwapRouterAddress,
				funcGetAmountsOut);

		String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcGetAmountsOut.getOutputParameters());

		DynamicArray<Uint256> dynamicArray = (DynamicArray<Uint256>) list.get(0);
		List<Uint256> outputList = dynamicArray.getValue();

		return outputList.get(outputList.size() - 1);
	}

	private Contract.TriggerSmartContract createFrontTrigger(String sHexpath1Address, long lAmountIn,
	                                                         Uint256 uint256AmountOut, long lDeadline) {
		Function funcSwapExactETHForTokens = new Function("swapExactETHForTokens", Arrays.asList(uint256AmountOut,
				new DynamicArray<>(Address.class, typeMap(Arrays.asList(sTrc20WtrxAddress, sHexpath1Address), Address.class))
				, addressWallet, new Uint256(lDeadline)), Collections.emptyList());

		String encoded = FunctionEncoder.encode(funcSwapExactETHForTokens);

		Contract.TriggerSmartContract trigger =
				Contract.TriggerSmartContract.newBuilder().setOwnerAddress(bsWalletAddress).setCallValue(lAmountIn).setContractAddress(bsSunSwapRouterAddress).setData(ApiWrapper.parseHex(encoded)).build();

		return trigger;
	}

	private Transaction callAndSignFront(Contract.TriggerSmartContract trigger) {
		TransactionExtention txnExt = apiWrapper.blockingStub.triggerConstantContract(trigger);
		Transaction unsignedTxn =
				txnExt.getTransaction().toBuilder().setRawData(txnExt.getTransaction().getRawData().toBuilder().setFeeLimit(lGasLimit)).build();

		// Sign and return the transaction
		return apiWrapper.signTransaction(unsignedTxn);
	}

	@Async
	protected CompletableFuture<Void>[] buildFrontTransaction(String sHexpath1Address, long lAmountIn,
	                                                          Uint256 uint256AmountOut, long lDeadline, int nCopyCount,
	                                                          java.util.function.Function<TransactionExtension,
			                                                          CompletableFuture<Void>> funcCallback) {

		// Prepare the function for swapping
		Contract.TriggerSmartContract trigger = createFrontTrigger(sHexpath1Address, lAmountIn, uint256AmountOut,
				lDeadline);

		CompletableFuture<Void>[] futures = new CompletableFuture[nCopyCount];

		// Create and add the CompletableFutures to the array
		for (int i = 0; i < nCopyCount; i++) {
			Transaction trx = callAndSignFront(trigger);
			int finalI = i;
			futures[i] = CompletableFuture.runAsync(() -> {
				funcCallback.apply(new TransactionExtension(trx, TransanctionType.FRONT, finalI));
			});
		}

		// Return the array of CompletableFutures
		return futures;
	}

	private Function createBackFunction(String sHexpath1Address, Uint256 lAmountIn, Uint256 uint256AmountOut,
	                                    long lDeadline) {
		return new Function("swapExactTokensForETH", Arrays.asList(uint256AmountOut, lAmountIn,
				new DynamicArray<>(Address.class, typeMap(Arrays.asList(sHexpath1Address, sTrc20WtrxAddress), Address.class))
				, addressWallet, new Uint256(lDeadline)), Collections.emptyList());
	}

	private Transaction callAndSignBack(String sContractAddress, Function function) {
		return apiWrapper.signTransaction(apiWrapper.triggerCall(sWalletHexAddress, sContractAddress, function).setFeeLimit(lGasLimit).build());
	}

	@Async
	protected CompletableFuture<Void>[] buildBackTransaction(String sHexpath1Address, long lAmountIn,
	                                                         Uint256 uint256AmountOut, long lDeadline, int nCopyCount,
	                                                         java.util.function.Function<TransactionExtension,
			                                                         CompletableFuture<Void>> funcCallback) {
		Function funcBack = createBackFunction(sHexpath1Address, new Uint256(lAmountIn), uint256AmountOut, lDeadline);
		CompletableFuture<Void>[] futures = new CompletableFuture[nCopyCount];

		// Create and add the CompletableFutures to the array
		for (int i = 0; i < nCopyCount; i++) {
			Transaction trx = callAndSignBack(Constant.sContractSunSwapRouterAddress, funcBack);
			int finalI = i;
			futures[i] = CompletableFuture.runAsync(() -> {
				funcCallback.apply(new TransactionExtension(trx, TransanctionType.BACK, finalI));
			});
		}

		// Return the array of CompletableFutures
		return futures;
	}

	@Async
	protected CompletableFuture<Void> BroadcastWithGrpc(TransactionExtension txExt) {
		return CompletableFuture.runAsync(() -> {
			double timestamp0 = System.currentTimeMillis();
			double timestamp1;
			boolean status = false;
			try {
				apiWrapper.broadcastTransaction(txExt.transaction);  // Broadcast the transaction
				timestamp1 = System.currentTimeMillis();
				status = true;
			} catch (RuntimeException e) {
				timestamp1 = System.currentTimeMillis();
				System.out.println(e.getMessage());
			}
			String output = "Type: " + txExt.eType + " Index: " + txExt.nIndex + " Status: " + status + " GRPC " + timestamp0 + " - " + timestamp1 + " = " + (timestamp1 - timestamp0) + "\n";
			MyLogger.print(output);
		});
	}

	public Uint256 balanceOf(String sTrc20ContractAddress) {
		try {
			Function balanceOf = new Function("balanceOf", Collections.singletonList(addressWallet),
					Collections.singletonList(new TypeReference<Uint256>() {
					}));

			org.tron.trident.proto.Response.TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress,
					sTrc20ContractAddress, balanceOf);
			String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

			List<Type> list = FunctionReturnDecoder.decode(result, balanceOf.getOutputParameters());
			Uint256 balance = (Uint256) list.get(0);

			return balance;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Uint256 getApproval(String sTrc20ContractAddress) {
		Function funcAllowance = new Function("allowance", Arrays.asList(addressWallet,
				new Address(Constant.sContractSunSwapRouterAddress)), Collections.singletonList(new TypeReference<Uint256>() {
		}));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sTrc20ContractAddress, funcAllowance);

		String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcAllowance.getOutputParameters());

		return (Uint256) list.get(0);
	}

	private void approve(String sTrc20ContractAddress) {
		String hexValue = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
		BigInteger bigInt = new BigInteger(hexValue, 16);

		Function approve = new Function("approve", Arrays.asList(new Address(Constant.sContractSunSwapRouterAddress),
				new Uint256(bigInt)), Collections.singletonList(new TypeReference<Uint256>() {
		}));

		Chain.Transaction transaction = callAndSignBack(sTrc20ContractAddress, approve);

		BroadcastTransaction(new TransactionExtension(transaction, TransanctionType.APPROVE, 0));
	}

	private void liquidate(String sTrc20ContractAddress, boolean forProfit) {

		Uint256 balanceOf = balanceOf(sTrc20ContractAddress);

		if (balanceOf.getValue().equals(BigInteger.ZERO)) {
			return;
		}

		BigInteger biOutAmount = BigInteger.ONE;
		Long lDeaqdline = System.currentTimeMillis() / 1000L;

		if (forProfit) {
			biOutAmount =
					getOutputExpectation(Arrays.asList(sTrc20ContractAddress, sTrc20WtrxAddress), balanceOf).getValue();
			if (biOutAmount.equals(BigInteger.ZERO)) {
				return;
			}
			biOutAmount.add(BigInteger.ONE);
			lDeaqdline += 5;
		} else {
			lDeaqdline += 1000;
		}

		Function funcBack = createBackFunction(sTrc20ContractAddress, new Uint256(biOutAmount), balanceOf, lDeaqdline);

		// Create and add the CompletableFutures to the array
		Transaction trx = callAndSignBack(Constant.sContractSunSwapRouterAddress, funcBack);
		BroadcastTransaction(new TransactionExtension(trx, TransanctionType.LIQUIDATE, 0));

	}

	@Async
	public void execute(String sHexPath1Address, ExecuteLog executeLog) {

		double doubleAmountIn = trx_min + Math.random() * (trx_max - trx_min);
		int nFrontCount = count1_min + (int) (Math.random() * (count1_max - count1_min));
		int nBackCount = count2_min + (int) (Math.random() * (count2_max - count2_min));
		long lAmountIn = (long) (doubleAmountIn * lOneTrx);

		CompletableFuture.runAsync(() -> {
			if (getApproval(sHexPath1Address).equals(Uint256.DEFAULT)) {
				approve(sHexPath1Address);
			}
		});

		long lDeadline = System.currentTimeMillis() / 1000 + 5;
		Uint256 uint256OutAmountExpected = getOutputExpectation(Arrays.asList(sTrc20WtrxAddress, sHexPath1Address),
				new Uint256(lAmountIn));

		executeLog.reactTimestamp = System.currentTimeMillis();
		executeLog.reactAmount0 = lAmountIn;
		executeLog.reactAmount1 = uint256OutAmountExpected.getValue();

		buildFrontTransaction(sHexPath1Address, lAmountIn, uint256OutAmountExpected, lDeadline, nFrontCount, (txExt) -> {
			BroadcastTransaction(txExt);
			return null;
		});

		buildBackTransaction(sHexPath1Address, (long) (lAmountIn * 0.994 + 1), uint256OutAmountExpected, lDeadline,
				nBackCount, (txExt) -> {
					BroadcastTransaction(txExt);
					return null;
				});

		ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
		scheduler.schedule(() -> {
			liquidate(sHexPath1Address, false);
			scheduler.shutdown();
		}, 3, TimeUnit.SECONDS);
	}

	@Async
	public CompletableFuture<ResponseBody> httpBroadcast(Request request) {
		CompletableFuture<ResponseBody> future = new CompletableFuture<ResponseBody>();
		okHttpClient.newCall(request).enqueue(new Callback() {
			@Override
			public void onFailure(Call call, IOException e) {
				System.out.println(request.url() + "\n" + e.getMessage());
				future.completeExceptionally(e);  // Complete the future exceptionally in case of failure
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException {
				ResponseBody responseBody = response.body();
				System.out.print(request.url() + "\n" + "success");
				response.close();
				future.complete(responseBody);  // Complete the future successfully with the response
			}
		});

		return future;
	}

	@Async
	public CompletableFuture<Void> BroadcastWithHttpBulk(TransactionExtension txExt) {
		String sHexRaw = Numeric.toHexString(txExt.transaction.toByteArray());

		MediaType mediaType = MediaType.parse("application/json");
		RequestBody body = RequestBody.create(mediaType, String.format("{\"transaction\":\"%s\"}", sHexRaw));
		Request.Builder builderTemplate = new Request.Builder();
		builderTemplate.method("POST", body);
		builderTemplate.addHeader("Content-Type", "application/json");
		builderTemplate.addHeader("Accept", "application/json");

		ArrayList<CompletableFuture<ResponseBody>> futures = new ArrayList<>();

		sUrls.forEach((String key, String[] value) -> {
			Request.Builder builder = builderTemplate;
			builder.url(value[0] + "/wallet/broadcasthex");
			for (int j = 1; j < value.length; j += 2) {
				builder.addHeader(value[j], value[j + 1]);
			}
			Request request = builder.build();
			CompletableFuture<ResponseBody> future = httpBroadcast(request);
			double timestamp0 = System.currentTimeMillis();
			future.thenAccept(response -> {
				double timestamp1 = System.currentTimeMillis();
				boolean status = !future.isCompletedExceptionally();
				String output = "Type: " + txExt.eType + " Index: " + txExt.nIndex + " Status: " + status + " " + key + " " + timestamp0 + " - " + timestamp1 + " = " + (timestamp1 - timestamp0) + "\n";
				MyLogger.print(output);
			});
			futures.add(future);  // Add each asynchronous httpBroadcast call to the list
		});

		// Combine all futures and wait for them to complete
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));  // Return null after all futures
		// complete
	}

	@Async
	protected CompletableFuture<Void> BroadcastTransaction(TransactionExtension transaction) {
		CompletableFuture<Void> grpcFuture = BroadcastWithGrpc(transaction);
		CompletableFuture<Void> httpBulkFuture = BroadcastWithHttpBulk(transaction);

//		return httpBulkFuture;
		return CompletableFuture.allOf(grpcFuture, httpBulkFuture);
//    return CompletableFuture.completedFuture(null);
	}
}
