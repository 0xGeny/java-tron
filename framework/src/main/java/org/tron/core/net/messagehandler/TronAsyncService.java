package org.tron.core.net.messagehandler;

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

	static KeyPair keyPair = new KeyPair("61ed92a0c8d164e3e40fd72e9babf730e23e250490e885f266afdca722b7222f");
	//    static ApiWrapper wrapper = new ApiWrapper("127.0.0.1:50051", "127.0.0.1:50051", keyPair.toPrivateKey());
	static ApiWrapper wrapper = ApiWrapper.ofMainnet(keyPair.toPrivateKey());

	@Async
	public CompletableFuture<BigInteger> getAmountOut(long amount, String meme_contract) {
		try {
			List<String> path = Arrays.asList(WTRX_Address, meme_contract);

			Function getAmount = new Function("getAmountsOut", Arrays.asList(new Uint256(amount),
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
	public CompletableFuture<Void> swapExactTokensForETH(long amount, BigInteger meme_amount, String meme_contract,
	                                                     long deadline) {
		try {
			List<String> path = Arrays.asList(meme_contract, WTRX_Address);

			Function swapExactTokensForETH = new Function("swapExactTokensForETH", Arrays.asList(new Uint256(meme_amount)
					, new Uint256((long) (amount * 0.994 + 1))
					, new DynamicArray<>(Address.class, typeMap(path, Address.class)), new Address(
							keyPair.toHexAddress()), new Uint256(deadline)),
					Collections.emptyList());

			wrapper.broadcastTransaction(wrapper.signTransaction(wrapper.triggerCall(keyPair.toHexAddress(),
					routerAddress, swapExactTokensForETH).setFeeLimit(feelimit).build()));

			return CompletableFuture.completedFuture(null);
		} catch (Exception e) {
			e.printStackTrace();
			return CompletableFuture.completedFuture(null);
		}
	}

	@Async
	public CompletableFuture<Void> approve(String meme_contract) {
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
			e.printStackTrace();
			return CompletableFuture.completedFuture(null);
		}
	}
}
