package org.tron.core.net.service;

public class Constant {

	public static String sTrc20WtrxAddressT = "TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR";
	public static String sTrc20WtrxAddressH = "891cdb91d149f23B1a45D9c5Ca78a88d0cB44C18";
	//  public static String sContractSunSwapRouterAddressT = "TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB";
	public static String sContractSunSwapRouterAddressH = "41fF7155b5df8008fbF3834922B2D52430b27874f5";
	public static String sTrc20SundogAddressT = "TXL6rJbvmjD46zeN1JssfgxvSo99qC8MRT";
	public static String sTrc20SundogAddressH = "EA4e3dFAcdf6D5b25f74ce4B689D79105043583C";
	public static long lGasLimit = 10000000L;
	public static String sUrlGrpcEndpoint = "127.0.0.1:50051";
	public static String sUrlGrpcSolidityEndpoint = "127.0.0.1:50051";
	public static int nTrxDecimals = 6;
	public static long lOneTrx = (long) Math.pow(10, nTrxDecimals);

	public static String[][] sUrls = new String[][]{
//      new String[]{"https://rpc.ankr.com/http/tron", null}
			new String[]{"https://rpc.ankr.com/premium-http/tron/28d38052bc89598fee2f0cf2c33ff9c9a56b7e96ebd154b2a0bcdcdfa0410dbb", null}, // 30 RPS, Ankr
			new String[]{"https://rpc.ankr.com/premium-http/tron/28a497a18af78fc19f1490d27482a9f2337f3b8d4353638466b69627797eec94", null}, // 30 RPS, Ankr
			new String[]{"https://rpc.ankr.com/premium-http/tron/cc35a00b5217441f1ad53d2e7757c53beae9fe1c66096a5896bf7255e1e2b9a8", null}, // 30 RPS, Ankr
//      new String[]{"https://tron-mainnet.gateway.tatum.io", "x-api-key", "t-66e2fd01402072e5439fcaa8-67e95bd0c24e45a6874a2c46"}, //  3 RPS, Tatum
      new String[]{"https://trx.nownodes.io/5babdeb5-d7bb-42e5-9823-00d55d89c1cb", null}, // 15 RPS, NOWNodes

	};
}