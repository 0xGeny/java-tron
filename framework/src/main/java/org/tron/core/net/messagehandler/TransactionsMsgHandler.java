package org.tron.core.net.messagehandler;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.*;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.osgi.framework.util.ArrayMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.utils.StringUtil;
import org.tron.core.config.args.Args;
import org.tron.core.exception.P2pException;
import org.tron.core.exception.P2pException.TypeEnum;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.message.TronMessage;
import org.tron.core.net.message.adv.TransactionMessage;
import org.tron.core.net.message.adv.TransactionsMessage;
import org.tron.core.net.peer.Item;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.service.adv.AdvService;
import org.tron.protos.Protocol.Inventory.InventoryType;
import org.tron.protos.Protocol.ReasonCode;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.convertToTronAddress;
import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.encode58Check;

import org.tron.protos.contract.SmartContractOuterClass;
import org.tron.trident.abi.datatypes.*;

import static org.tron.core.services.jsonrpc.JsonRpcApiUtil.*;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

@Slf4j(topic = "net")
@Component
public class TransactionsMsgHandler implements TronMsgHandler {

	private static int MAX_TRX_SIZE = 50_000;
	private static int MAX_SMART_CONTRACT_SUBMIT_SIZE = 100;
	@Autowired
	private TronNetDelegate tronNetDelegate;
	@Autowired
	private AdvService advService;

	private BlockingQueue<TrxEvent> smartContractQueue = new LinkedBlockingQueue(MAX_TRX_SIZE);

	private BlockingQueue<Runnable> queue = new LinkedBlockingQueue();

	private int threadNum = Args.getInstance().getValidateSignThreadNum();
	private final String trxEsName = "trx-msg-handler";
	private ExecutorService trxHandlePool = ExecutorServiceManager.newThreadPoolExecutor(
			threadNum, threadNum, 0L,
			TimeUnit.MILLISECONDS, queue, trxEsName);
	private final String smartEsName = "contract-msg-handler";
	private final ScheduledExecutorService smartContractExecutor = ExecutorServiceManager
			.newSingleThreadScheduledExecutor(smartEsName);

	static class transactionLog {
		public String key1;
		public ArrayMap<String, Integer> logs;

		transactionLog(String key) {
			key1 = key;
			logs = new ArrayMap<>(0);
		}

		public transactionLog add(String key2) {
			if (logs.get(key2) == null) logs.put(key2, 1);
			else logs.put(key2, logs.get(key2) + 1);

			return this;
		}
	}

	ArrayMap<String, transactionLog> transactionLogs = new ArrayMap<>(0);
	ArrayMap<String, transactionLog> peerLogs = new ArrayMap<>(0);

	Logger myLogger = Logger.getLogger("MyLog");
	FileHandler fh;

	public void init() {
		handleSmartContract();
		try {
			// This block configure the logger with handler and formatter
			fh = new FileHandler("./MyLogFile.log");
			myLogger.addHandler(fh);
			SimpleFormatter formatter = new SimpleFormatter();
			fh.setFormatter(formatter);

			// the following statement is used to log any messages

		} catch (SecurityException | IOException ignored) {
		}
	}

	public void close() {
		ExecutorServiceManager.shutdownAndAwaitTermination(trxHandlePool, trxEsName);
		ExecutorServiceManager.shutdownAndAwaitTermination(smartContractExecutor, smartEsName);
	}

	public boolean isBusy() {
		return queue.size() + smartContractQueue.size() > MAX_TRX_SIZE;
	}

	@Override
	public void processMessage(PeerConnection peer, TronMessage msg) throws P2pException {
		TransactionsMessage transactionsMessage = (TransactionsMessage) msg;
		check(peer, transactionsMessage);
		int smartContractQueueSize = 0;
		int trxHandlePoolQueueSize = 0;
		int dropSmartContractCount = 0;
		for (Transaction trx : transactionsMessage.getTransactions().getTransactionsList()) {
			int type = trx.getRawData().getContract(0).getType().getNumber();
			if (type == ContractType.TriggerSmartContract_VALUE
					|| type == ContractType.CreateSmartContract_VALUE) {
				if (!smartContractQueue.offer(new TrxEvent(peer, new TransactionMessage(trx)))) {
					smartContractQueueSize = smartContractQueue.size();
					trxHandlePoolQueueSize = queue.size();
					dropSmartContractCount++;
				}
			} else {
				trxHandlePool.submit(() -> handleTransaction(peer, new TransactionMessage(trx)));
			}
		}

		if (dropSmartContractCount > 0) {
			logger.warn("Add smart contract failed, drop count: {}, queueSize {}:{}",
					dropSmartContractCount, smartContractQueueSize, trxHandlePoolQueueSize);
		}
	}

	private void check(PeerConnection peer, TransactionsMessage msg) throws P2pException {
		for (Transaction trx : msg.getTransactions().getTransactionsList()) {
			Item item = new Item(new TransactionMessage(trx).getMessageId(), InventoryType.TRX);
			if (!peer.getAdvInvRequest().containsKey(item)) {
				throw new P2pException(TypeEnum.BAD_MESSAGE,
						"trx: " + msg.getMessageId() + " without request.");
			}
			peer.getAdvInvRequest().remove(item);
		}
	}

	@Autowired
	private TronAsyncService tronAsyncService;

	private void handleChance(PeerConnection peer, TransactionMessage trx) {
		try {
			Transaction transaction = Transaction.parseFrom(trx.getTransactionCapsule().getData());
			if (transaction.getRawData().getContractCount() > 0) {
				Transaction.Contract contract = transaction.getRawData().getContract(0);
				if (contract.getType() == ContractType.TriggerSmartContract) {
					SmartContractOuterClass.TriggerSmartContract triggerSmartContract =
							contract.getParameter().unpack(SmartContractOuterClass.TriggerSmartContract.class);
					String contractAddress =
							StringUtil.encode58Check(triggerSmartContract.getContractAddress().toByteArray());

					if (contractAddress.equals("TZFs5ch1R1C4mmjwrrmZqeqbUgGpxY1yWB")) {
						byte[] data = triggerSmartContract.getData().toByteArray();
						byte[] method = Arrays.copyOfRange(data, 0, 4);

						long timestamp = trx.getTransactionCapsule().getTimestamp();
						long now = System.currentTimeMillis();
						long amountIn = 0L;
						long amountOutMin = 0L;
						byte[] bytesPath0 = null;
						byte[] bytesPath1 = null;
						byte[] bytesToAddress = null;
						long deadline = 0L;

						if (Arrays.equals(method, new byte[]{(byte) 0x18, (byte) 0xcb, (byte) 0xaf, (byte) 0xe5})) {
							// token -> trx
						}

						if (Arrays.equals(method, new byte[]{(byte) 0x38, (byte) 0xed, (byte) 0x17, (byte) 0x39})) {
							// token (WTRX) -> token

//							swapExactTokensForTokens(uint256 amountIn, uint256 amountOutMin, address[] path, address to, uint256
//							deadline)
//							MethodID:    38ed1739
//									[0]:   000000000000000000000000000000000000000000000000000000024605ae84
//									[1]:   000000000000000000000000000000000000000000000156335d57f186700000
//									[2]:   00000000000000000000000000000000000000000000000000000000000000a0
//									[3]:   00000000000000000000000063662d8a3e812969b4e716c598c35e0c2c1d4368
//									[4]:   00000000000000000000000000000000000000000000000000000000670d6836
//									[5]:   0000000000000000000000000000000000000000000000000000000000000002
//									[6]:   000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18
//									[7]:   000000000000000000000000ea4e3dfacdf6d5b25f74ce4b689d79105043583c
							byte[] bytesAmountIn = Arrays.copyOfRange(data, 4, 4 + 0x20);
							byte[] bytesAmountOut = Arrays.copyOfRange(data, 4 + 0x20, 4 + 0x40);
							byte[] bytesOffset = Arrays.copyOfRange(data, 4 + 0x40, 4 + 0x60);
							bytesToAddress = Arrays.copyOfRange(data, 4 + 0x60, 4 + 0x80);
							byte[] bytesDeadline = Arrays.copyOfRange(data, 4 + 0x80, 4 + 0xA0);

							int offset = ByteBuffer.wrap(bytesOffset, bytesOffset.length - 4, 4).getInt();
							byte[] bytesCount = Arrays.copyOfRange(data, 4 + offset, 4 + offset + 0x20);

							int arraylength = ByteBuffer.wrap(bytesCount, bytesCount.length - 4, 4).getInt();

							bytesPath0 = Arrays.copyOfRange(data, 4 + offset + 0x20, 4 + offset + 0x20 * 2);
							bytesPath1 = Arrays.copyOfRange(data, 4 + offset + arraylength * 0x20,
									4 + offset + arraylength * 0x20 + 0x20);

							amountIn = new BigInteger(1, bytesAmountIn).longValue();
							amountOutMin = new BigInteger(1, bytesAmountOut).longValue();
							deadline = new BigInteger(1, bytesDeadline).longValue();
						}

						if (Arrays.equals(method, new byte[]{(byte) 0xfb, (byte) 0x3b, (byte) 0xdb, (byte) 0x41})) {
							// trx -> token 0xfb3bdb41


//							swapETHForExactTokens(uint256 amountOut, address[] path, address to, uint256 deadline)
//							MethodID:    fb3bdb41
//									[0]:   0000000000000000000000000000000000000000000009bd4f5afc71ae680000
//									[1]:   0000000000000000000000000000000000000000000000000000000000000080
//									[2]:   0000000000000000000000008cf67cc2fbf0267f92550d70e248c134fdae42bd
//									[3]:   00000000000000000000000000000000000000000000000000000000670d8107
//									[4]:   0000000000000000000000000000000000000000000000000000000000000002
//									[5]:   000000000000000000000000891cdb91d149f23b1a45d9c5ca78a88d0cb44c18
//									[6]:   000000000000000000000000a8206c1fda9ed9c73e787ea1da2ac75a354df2e1

							byte[] bytesAmountOut = Arrays.copyOfRange(data, 4, 4 + 0x20);
							byte[] bytesOffset = Arrays.copyOfRange(data, 4 + 0x20, 4 + 0x40);
							bytesToAddress = Arrays.copyOfRange(data, 4 + 0x40, 4 + 0x60);
							byte[] bytesDeadline = Arrays.copyOfRange(data, 4 + 0x60, 4 + 0x80);

							int offset = ByteBuffer.wrap(bytesOffset, bytesOffset.length - 4, 4).getInt();
							byte[] bytesCount = Arrays.copyOfRange(data, 4 + offset, 4 + offset + 0x20);

							int arraylength = ByteBuffer.wrap(bytesCount, bytesCount.length - 4, 4).getInt();
							bytesPath0 = Arrays.copyOfRange(data, 4 + offset + 0x20, 4 + offset + 0x20 * 2);
							bytesPath1 = Arrays.copyOfRange(data, 4 + offset + arraylength * 0x20,
									4 + offset + arraylength * 0x20 + 0x20);

							amountIn = triggerSmartContract.getCallValue();
							amountOutMin = new BigInteger(1, bytesAmountOut).longValue();
						}

						if (bytesToAddress == null || bytesPath0 == null || bytesPath1 == null) {
							return;
						}

						if (amountIn < 1000 * 1000000L) return;

						String toAddress = encode58Check(convertToTronAddress(bytesToAddress));
						String toPath0 = encode58Check(convertToTronAddress(bytesPath0));
						String toPath1 = encode58Check(convertToTronAddress(bytesPath1));

						if (!toPath0.equals("TNUC9Qb1rRpS5CbWLmNMxXBjyFoydXjWFR")) {
							// not WTRX
							return;
						}

						ArrayList<String> blacklist = new ArrayList<>(
								Arrays.asList("TPsUGKAoXDSFz332ZYtTGdDHWzftLYWFj7",
										"TEtPcNXwPj1PEdsDRCZfUvdFHASrJsFeW5",
										"TN2EQwZpKE5UrShg11kHGyRth7LF5GbRPC",
										"TJf7YitKX2QU5M2kW9hmcdjrAbEz4T5NyQ",
										"TXtARmXejKjroz51YJVkFcdciun8YcU9nn",
										"TLJuomNsHx76vLosaW3Tz3MFTqCANL8v5m",
										"TSMEzJhS5vrWqy9VNLcRjjNuzrMqnRcMbQ"));

						if (blacklist.contains(toPath0)) {
							// blacklist

							return;
						}

						String inetSocketAddress = peer.getInetSocketAddress().toString();

						if (transactionLogs.get(toAddress) == null) {
							transactionLogs.put(toAddress, new transactionLog(toAddress));
						}
						transactionLogs.put(toAddress, transactionLogs.get(toAddress).add(inetSocketAddress));

						if (peerLogs.get(inetSocketAddress) == null) {
							peerLogs.put(inetSocketAddress, new transactionLog(inetSocketAddress));
						}
						peerLogs.put(inetSocketAddress, peerLogs.get(inetSocketAddress).add(toAddress));

						myLogger.info(String.format("%d - %d = %d\nFrom %s %s %d TRX -> %s token %s%n", now,
								timestamp, now - timestamp, peer.getInetSocketAddress(), toAddress, amountIn,
								amountOutMin, toPath1));

						System.out.printf("%d - %d = %d\nFrom %s %s %d TRX -> %s token %s%n", now, timestamp,
								now - timestamp, peer.getInetSocketAddress(), toAddress, amountIn,
								amountOutMin, toPath1);
						try {
//                                long trx_amount = (long) (Math.random() * 100);
							long trx_amount = 10;
							long amount = trx_amount * 1000000L;

							ArrayList<String> approved = new ArrayList<>(
									Arrays.asList("TAt4ufXFaHZAEV44ev7onThjTnF61SEaEM",
											"TCGPc27oyS2x7S5pex7ssyZxZ2edPWonk2",
											"TE2T2vLnEQT1XW647EAQAHWqd6NZL1hweR",
											"TPeoxx1VhUMnAUyjwWfximDYFDQaxNQQ45",
											"TF7ixydn7nfCgj9wQj3fRdKRAvsZ8egHcx",
											"TQzUXP5eXHwrRMou9KYQQq7wEmu8KQF7mX",
											"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
											"TRGEYcmBSAz3PswhtAHcUPjGbuGr1H9Fza",
											"TSig7sWzEL2K83mkJMQtbyPpiVSbR6pZnb",
											"TVXmroHbJsJ6rVm3wGn2G9723yz3Kbqp9x",
											"TWjuiXpamjvm6DeuAUE5vAusQ2QiyQr5JY",
											"TXL6rJbvmjD46zeN1JssfgxvSo99qC8MRT")
							);

							if (!approved.contains(toPath1)) {
								return;
							}

							CompletableFuture<BigInteger> amountOutFuture = tronAsyncService.getAmountOut(amount,
									toPath1);
							amountOutFuture.thenAccept(amountOut -> {
//                                    System.out.println("Amount out: " + amountOut);
//                                    CompletableFuture<Void> approveFuture = tronAsyncService.approve(meme_contract);
//                                    approveFuture.thenRun(() -> {
//                                        CompletableFuture<Void> swapTokensFuture = tronAsyncService
//                                        .swapExactETHForTokens(amount, amountOut, meme_contract);
//                                        swapTokensFuture.join();  // Wait for the swap to complete
//                                        System.out.println("Swap completed!");
//                                    });

								if (System.currentTimeMillis() - timestamp > 0 && System.currentTimeMillis() - timestamp < 250) {
									System.out.println("Run bot");
									long new_deadline = (int) (transaction.getRawData().getTimestamp() / 1000) + 3;
									int count1 = (int) (Math.random() * 3 + 2);
									int count2 = count1 + (int) (Math.random() * 3);
									for (int i = 0; i < count1; i++) {
										tronAsyncService.swapExactETHForTokens(amount, amountOut, toPath1, new_deadline);
									}
									for (int i = 0; i < count2; i++) {
										tronAsyncService.swapExactTokensForETH(amount, amountOut, toPath1, new_deadline);
									}
								}
							}).exceptionally(ex -> {
								System.err.println("Error occurred: " + ex.getMessage());
								return null;
							});

						} catch (Exception ignored) {
						}
//                            System.out.println(getAmountOut(100 * 1000000L, toPath1));
					}
				}
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}

	private void handleSmartContract() {
		smartContractExecutor.scheduleWithFixedDelay(() -> {
			try {
				while (queue.size() < MAX_SMART_CONTRACT_SUBMIT_SIZE && !smartContractQueue.isEmpty()) {
					TrxEvent event = smartContractQueue.take();
					trxHandlePool.submit(() -> handleTransaction(event.getPeer(), event.getMsg()));
				}
			} catch (InterruptedException e) {
				logger.warn("Handle smart server interrupted");
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				logger.error("Handle smart contract exception", e);
			}
		}, 1000, 20, TimeUnit.MILLISECONDS);
	}

	private void handleTransaction(PeerConnection peer, TransactionMessage trx) {
		if (peer.isBadPeer()) {
			logger.warn("Drop trx {} from {}, peer is bad peer", trx.getMessageId(),
					peer.getInetAddress());
			return;
		}

		if (advService.getMessage(new Item(trx.getMessageId(), InventoryType.TRX)) != null) {
			return;
		}

		handleChance(peer, trx);

//    try {
//      tronNetDelegate.pushTransaction(trx.getTransactionCapsule());
//      advService.broadcast(trx);
//    } catch (P2pException e) {
//      logger.warn("Trx {} from peer {} process failed. type: {}, reason: {}",
//          trx.getMessageId(), peer.getInetAddress(), e.getType(), e.getMessage());
//      if (e.getType().equals(TypeEnum.BAD_TRX)) {
//        peer.setBadPeer(true);
//        peer.disconnect(ReasonCode.BAD_TX);
//      }
//    } catch (Exception e) {
//      logger.error("Trx {} from peer {} process failed", trx.getMessageId(), peer.getInetAddress(),
//          e);
//    }
	}

	class TrxEvent {

		@Getter
		private PeerConnection peer;
		@Getter
		private TransactionMessage msg;
		@Getter
		private long time;

		public TrxEvent(PeerConnection peer, TransactionMessage msg) {
			this.peer = peer;
			this.msg = msg;
			this.time = System.currentTimeMillis();
		}
	}
}