package org.tron.core.net.service;


import com.google.protobuf.ByteString;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.tron.trident.abi.FunctionEncoder;
import org.tron.trident.abi.FunctionReturnDecoder;
import org.tron.trident.abi.TypeReference;
import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.DynamicArray;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.abi.datatypes.Type;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.proto.Chain.Transaction;
import org.tron.trident.proto.Contract;
import org.tron.trident.proto.Response.TransactionExtention;
import org.tron.trident.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.tron.core.net.service.Constant.*;
import static org.tron.trident.abi.Utils.typeMap;
import static org.tron.trident.core.ApiWrapper.parseAddress;

@Service
public class SandwichService {

	private final ByteString bsSunSwapRouterAddress = parseAddress(sContractSunSwapRouterAddressH);
	private final OkHttpClient okHttpClient = new OkHttpClient();
	private String PK = null;
	private ApiWrapper apiWrapper = null;
	private String sWalletHexAddress = null;
	private ByteString bsWalletAddress = null;
	private Address addressWallet = null;
	private long trx_min = 0;
	private long trx_max = 0;
	private int count1_min = 0;
	private int count1_max = 0;
	private int count2_min = 0;
	private int count2_max = 0;

	private static SandwichService _instance = null;

	public static SandwichService getInstance() {
		if (_instance == null) {
			_instance = new SandwichService();
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

	private Uint256 getOutputExpectation(List<String> lstPath, Uint256 lAmountIn) {
		Function funcGetAmountsOut = new Function("getAmountsOut", Arrays.asList(lAmountIn, new DynamicArray<>(Address.class, typeMap(lstPath, Address.class))), Collections.singletonList(new TypeReference<DynamicArray<Uint256>>() {
		}));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sContractSunSwapRouterAddressH, funcGetAmountsOut);

		String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcGetAmountsOut.getOutputParameters());

		DynamicArray<Uint256> dynamicArray = (DynamicArray<Uint256>) list.get(0);
		List<Uint256> outputList = dynamicArray.getValue();

		return outputList.get(outputList.size() - 1);
	}

	private Contract.TriggerSmartContract createFrontTrigger(String sHexpath1Address, long lAmountIn, Uint256 uint256AmountOut, long lDeadline) {
		Function funcSwapExactETHForTokens = new Function("swapExactETHForTokens", Arrays.asList(uint256AmountOut, new DynamicArray<>(Address.class, typeMap(Arrays.asList(sTrc20WtrxAddressH, sHexpath1Address), Address.class)), addressWallet, new Uint256(lDeadline)), Collections.emptyList());

		String encoded = FunctionEncoder.encode(funcSwapExactETHForTokens);

		Contract.TriggerSmartContract trigger = Contract.TriggerSmartContract.newBuilder().setOwnerAddress(bsWalletAddress).setCallValue(lAmountIn).setContractAddress(bsSunSwapRouterAddress).setData(ApiWrapper.parseHex(encoded)).build();

		return trigger;
	}

	private Transaction callAndSignFront(Contract.TriggerSmartContract trigger) {
		TransactionExtention txnExt = apiWrapper.blockingStub.triggerConstantContract(trigger);
		Transaction unsignedTxn = txnExt.getTransaction().toBuilder().setRawData(txnExt.getTransaction().getRawData().toBuilder().setFeeLimit(lGasLimit)).build();

		// Sign and return the transaction
		return apiWrapper.signTransaction(unsignedTxn);
	}

	@Async
	protected CompletableFuture<Void>[] buildFrontTransaction(String sHexpath1Address, long lAmountIn, Uint256 uint256AmountOut, long lDeadline, int nCopyCount, java.util.function.Function<Transaction, CompletableFuture<Void>> funcCallback) {

		// Prepare the function for swapping
		Contract.TriggerSmartContract trigger = createFrontTrigger(sHexpath1Address, lAmountIn, uint256AmountOut, lDeadline);

		CompletableFuture<Void>[] futures = new CompletableFuture[nCopyCount];

		// Create and add the CompletableFutures to the array
		for (int i = 0; i < nCopyCount; i++) {
			Transaction trx = callAndSignFront(trigger);
			futures[i] = CompletableFuture.runAsync(() -> {
				funcCallback.apply(trx);
			});
		}

		// Return the array of CompletableFutures
		return futures;
	}

	private Function createBackFunction(String sHexpath1Address, Uint256 lAmountIn, Uint256 uint256AmountOut, long lDeadline) {
		return new Function("swapExactTokensForETH", Arrays.asList(uint256AmountOut, lAmountIn, new DynamicArray<>(Address.class, typeMap(Arrays.asList(sHexpath1Address, sTrc20WtrxAddressH), Address.class)), addressWallet, new Uint256(lDeadline)), Collections.emptyList());
	}

	private Transaction callAndSignBack(String sContractAddress, Function function) {
		return apiWrapper.signTransaction(apiWrapper.triggerCall(sWalletHexAddress, sContractAddress, function).setFeeLimit(lGasLimit).build());
	}

	@Async
	protected CompletableFuture<Void>[] buildBackTransaction(String sHexpath1Address, long lAmountIn, Uint256 uint256AmountOut, long lDeadline, int nCopyCount, java.util.function.Function<Transaction, CompletableFuture<Void>> funcCallback) {
		Function funcBack = createBackFunction(sHexpath1Address, new Uint256(lAmountIn), uint256AmountOut, lDeadline);
		CompletableFuture<Void>[] futures = new CompletableFuture[nCopyCount];

		// Create and add the CompletableFutures to the array
		for (int i = 0; i < nCopyCount; i++) {
			Transaction trx = callAndSignBack(sContractSunSwapRouterAddressH, funcBack);
			futures[i] = CompletableFuture.runAsync(() -> {
				funcCallback.apply(trx);
			});
		}

		// Return the array of CompletableFutures
		return futures;
	}

	@Async
	protected CompletableFuture<Void> BroadcastWithGrpc(Transaction transaction) {
		return CompletableFuture.runAsync(() -> {
			try {
				apiWrapper.broadcastTransaction(transaction);  // Broadcast the transaction
			} catch (RuntimeException e) {
				System.out.println(e.getMessage());
			}
		});
	}

	public Uint256 balanceOf(String sTrc20ContractAddress) {
		try {
			Function balanceOf = new Function("balanceOf", Collections.singletonList(addressWallet), Collections.singletonList(new TypeReference<Uint256>() {
			}));

			org.tron.trident.proto.Response.TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sTrc20ContractAddress, balanceOf);
			String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

			List<Type> list = FunctionReturnDecoder.decode(result, balanceOf.getOutputParameters());
			Uint256 balance = (Uint256) list.get(0);

			return balance;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Uint256 getApproval(String sTrc20ContractAddress) {
		Function funcAllowance = new Function("allowance", Arrays.asList(addressWallet, new Address(sContractSunSwapRouterAddressH)), Collections.singletonList(new TypeReference<Uint256>() {
		}));

		TransactionExtention txnExt = apiWrapper.constantCall(sWalletHexAddress, sTrc20ContractAddress, funcAllowance);

		String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

		List<Type> list = FunctionReturnDecoder.decode(result, funcAllowance.getOutputParameters());

		return (Uint256) list.get(0);
	}

	private void approve(String sTrc20ContractAddress) {
		String hexValue = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
		BigInteger bigInt = new BigInteger(hexValue, 16);

		Function approve = new Function("approve", Arrays.asList(new Address(sContractSunSwapRouterAddressH), new Uint256(bigInt)), Collections.singletonList(new TypeReference<Uint256>() {
		}));

		BroadcastTransaction(callAndSignBack(sTrc20ContractAddress, approve));
	}

	private void liquidate(String sTrc20ContractAddress, boolean forProfit) {

		Uint256 balanceOf = balanceOf(sTrc20ContractAddress);

		if (balanceOf.getValue().equals(BigInteger.ZERO)) {
			return;
		}

		BigInteger biOutAmount = BigInteger.ONE;
		Long lDeaqdline = System.currentTimeMillis() / 1000L;

		if (forProfit) {
			biOutAmount = getOutputExpectation(Arrays.asList(sTrc20ContractAddress, sTrc20WtrxAddressH), balanceOf).getValue();
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
		Transaction trx = callAndSignBack(sContractSunSwapRouterAddressH, funcBack);
		System.out.print(BroadcastTransaction(trx));

	}

	@Async
	public void doSandwich(String sHexPath1Address) {

		double doubleAmountIn = trx_min + Math.random() * (trx_max - trx_min);
		int nFrontCount = count1_min + (int) (Math.random() * (count1_max - count1_min));
		int nBackCount = count2_min + (int) (Math.random() * (count2_max - count2_min));
		long lAmountIn = (long) (doubleAmountIn * 1000000L);

		CompletableFuture.runAsync(() -> {
			if (getApproval(sHexPath1Address).equals(Uint256.DEFAULT)) {
				approve(sHexPath1Address);
			}
		});

		long lDeadline = System.currentTimeMillis() / 1000 + 5;
		Uint256 uint256OutAmountExpected = getOutputExpectation(Arrays.asList(sTrc20WtrxAddressH, sHexPath1Address), new Uint256(lAmountIn));
		CompletableFuture.runAsync(() -> {
			buildFrontTransaction(sHexPath1Address, lAmountIn, uint256OutAmountExpected, lDeadline, nFrontCount, (trx) -> {
				BroadcastTransaction(trx);
				return null;
			});
		});
		CompletableFuture.runAsync(() -> {
			buildBackTransaction(sHexPath1Address, lAmountIn, uint256OutAmountExpected, lDeadline, nBackCount, (trx) -> {
				BroadcastTransaction(trx);
				return null;
			});
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
				System.out.print(request.url() + "\n" + responseBody.string());
				response.close();
				future.complete(responseBody);  // Complete the future successfully with the response
			}
		});

		return future;
	}

	@Async
	public CompletableFuture<Void> BroadcastWithHttpBulk(Transaction transaction) {
		String sHexRaw = Numeric.toHexString(transaction.toByteArray());

		MediaType mediaType = MediaType.parse("application/json");
		RequestBody body = RequestBody.create(mediaType, String.format("{\"transaction\":\"%s\"}", sHexRaw));
		Request.Builder builderTemplate = new Request.Builder();
		builderTemplate.method("POST", body);
		builderTemplate.addHeader("Content-Type", "application/json");
		builderTemplate.addHeader("Accept", "application/json");

		CompletableFuture<ResponseBody>[] futures = new CompletableFuture[sUrls.length];

		for (int i = 0; i < sUrls.length; i++) {
			Request.Builder builder = builderTemplate;
			builder.url(sUrls[i][0] + "/wallet/broadcasthex");
			if (sUrls[i][1] != null) {
				builder.addHeader(sUrls[i][1], sUrls[i][2]);
			}
			Request request = builder.build();
			futures[i] = httpBroadcast(request);  // Add each asynchronous httpBroadcast call to the list
		}

		// Combine all futures and wait for them to complete
		return CompletableFuture.allOf(futures);  // Return null after all futures complete
	}

	@Async
	protected CompletableFuture<Void> BroadcastTransaction(Transaction transaction) {
		CompletableFuture<Void> grpcFuture = BroadcastWithGrpc(transaction);
		CompletableFuture<Void> httpBulkFuture = BroadcastWithHttpBulk(transaction);

		return CompletableFuture.allOf(grpcFuture, httpBulkFuture);
//    return CompletableFuture.completedFuture(null);
	}
}
