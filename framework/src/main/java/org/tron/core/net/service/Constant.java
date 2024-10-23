package org.tron.core.net.service;

public class Constant {
	public static String sTrc20WtrxAddress = "TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR";
	public static String sContractSunSwapRouterAddress = "TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB";
	public static long lGasLimit = 10000000L;
	public static String sUrlGrpcEndpoint = "127.0.0.1:50051";
	public static String sUrlGrpcSolidityEndpoint = "127.0.0.1:50051";
	public static int nTrxDecimals = 6;
	public static long lOneTrx = (long) Math.pow(10, nTrxDecimals);
}