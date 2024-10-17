package org.tron.core.net.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.tron.trident.abi.FunctionEncoder;
import org.tron.trident.abi.FunctionReturnDecoder;
import org.tron.trident.abi.TypeReference;
import org.tron.trident.abi.datatypes.*;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import org.tron.trident.proto.Response;
import org.tron.trident.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.tron.trident.abi.Utils.typeMap;

@Service
public class TronAsyncService {

	public static String WTRX_Address = "TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR";
	public static String routerAddress = "TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB";
	public static long feelimit = 10000000L;

	static KeyPair keyPair = null;
	//    static ApiWrapper wrapper = new ApiWrapper("127.0.0.1:50051", "127.0.0.1:50051", keyPair.toPrivateKey());
	static ApiWrapper wrapper = null;
	private static TronAsyncService _instance = null;

	public static TronAsyncService getInstance() {
		if (_instance == null) {
			_instance = new TronAsyncService();
		}

		return _instance;
	}

	public static void init() {
		if (wrapper != null) {
			wrapper.close();
		}

		keyPair = new KeyPair(EnvService.getInstance().get("PK"));
		wrapper = new ApiWrapper("127.0.0.1:50051", "127.0.0.1:50051", keyPair.toPrivateKey());
	}

	@Async
	public CompletableFuture<BigInteger> getAmountOut(Uint256 amount, List<String> path) {
		if (wrapper == null) {
			return CompletableFuture.completedFuture(BigInteger.ZERO);
		}
		try {
			Function getAmount = new Function("getAmountsOut", Arrays.asList(amount,
					new DynamicArray<>(Address.class, typeMap(path, Address.class))),
					Arrays.asList(new TypeReference<DynamicArray<Uint256>>() {
					}));

			Response.TransactionExtention txnExt = wrapper.constantCall(keyPair.toHexAddress(), routerAddress, getAmount);
			String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

			List<Type> list = FunctionReturnDecoder.decode(result, getAmount.getOutputParameters());
			DynamicArray<Uint256> dynamicArray = (DynamicArray<Uint256>) list.get(0);
			List<Uint256> outputList = dynamicArray.getValue();

			return CompletableFuture.completedFuture(outputList.get(outputList.size() - 1).getValue());
		} catch (Exception e) {
			e.printStackTrace();
			return CompletableFuture.completedFuture(BigInteger.ZERO);
		}
	}

	@Async
	public CompletableFuture<Void> swapExactETHForTokens(long amount, BigInteger meme_amount, String meme_contract,
	                                                     long deadline) {
		if (wrapper == null) {
			return CompletableFuture.completedFuture(null);
		}
		try {
			List<String> path = Arrays.asList(WTRX_Address, meme_contract);

			Function swapExactETHForTokens = new Function("swapExactETHForTokens", Arrays.asList(new Uint256(meme_amount)
					, new DynamicArray<>(Address.class, typeMap(path, Address.class)), new Address(
							keyPair.toHexAddress()), new Uint256(deadline)),
					Arrays.asList());

			String encoded = FunctionEncoder.encode(swapExactETHForTokens);

			Contract.TriggerSmartContract trigger =
					Contract.TriggerSmartContract.newBuilder().setOwnerAddress(ApiWrapper.parseAddress(keyPair.toHexAddress()))
							.setCallValue(amount)
							.setContractAddress(ApiWrapper.parseAddress(routerAddress)).setData(ApiWrapper.parseHex(encoded)).build();

			Response.TransactionExtention txnExt = wrapper.blockingStub.triggerConstantContract(trigger);
			Chain.Transaction unsignedTxn =
					txnExt.getTransaction().toBuilder().setRawData(txnExt.getTransaction().getRawData().toBuilder().setFeeLimit(feelimit)).build();

			Chain.Transaction signedTransaction = wrapper.signTransaction(unsignedTxn);

			wrapper.broadcastTransaction(signedTransaction);

			return CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			e.printStackTrace();
			return CompletableFuture.completedFuture(null);
		}
	}

	@Async
	public CompletableFuture<Void> swapExactTokensForETH(BigInteger amount, BigInteger meme_amount, String meme_contract,
	                                                     long deadline) {
		if (wrapper == null) {
			return CompletableFuture.completedFuture(null);
		}
		try {
			List<String> path = Arrays.asList(meme_contract, WTRX_Address);

			Function swapExactTokensForETH = new Function("swapExactTokensForETH", Arrays.asList(new Uint256(meme_amount)
					, new Uint256(amount)
					, new DynamicArray<>(Address.class, typeMap(path, Address.class)), new Address(
							keyPair.toHexAddress()), new Uint256(deadline)),
					Collections.emptyList());

			wrapper.broadcastTransaction(wrapper.signTransaction(wrapper.triggerCall(keyPair.toHexAddress(),
					routerAddress, swapExactTokensForETH).setFeeLimit(feelimit).build()));

			return CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(null);
		}
	}

	@Async
	public CompletableFuture<Void> approve(String meme_contract) {
		if (wrapper == null) {
			return CompletableFuture.completedFuture(null);
		}
		try {
			BigInteger bigInt = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

			Function approve = new Function("approve", Arrays.asList(new Address(routerAddress),
					new Uint256(bigInt)),
					Arrays.asList(new TypeReference<Bool>() {
					}));

			wrapper.broadcastTransaction(wrapper.signTransaction(wrapper.triggerCall(keyPair.toHexAddress(),
					meme_contract, approve).setFeeLimit(feelimit).build()));

			return CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			return CompletableFuture.completedFuture(null);
		}
	}

	@Async
	public CompletableFuture<Uint256> balanceOf(String meme_contract) {
		if (wrapper == null) {
			return CompletableFuture.completedFuture(Uint256.DEFAULT);
		}
		try {
			Function balanceOf = new Function("balanceOf", Arrays.asList(
					new Address(keyPair.toHexAddress())),
					Arrays.asList(new TypeReference<Uint256>(){}));

			Response.TransactionExtention txnExt = wrapper.constantCall(keyPair.toHexAddress(), meme_contract, balanceOf);
			String result = Numeric.toHexString(txnExt.getConstantResult(0).toByteArray());

			List<Type> list = FunctionReturnDecoder.decode(result, balanceOf.getOutputParameters());
			Uint256 balance = (Uint256) list.get(0);

			return CompletableFuture.completedFuture(balance);
		} catch (Exception e) {
			e.printStackTrace();
			return CompletableFuture.completedFuture(Uint256.DEFAULT);
		}
	}
}
