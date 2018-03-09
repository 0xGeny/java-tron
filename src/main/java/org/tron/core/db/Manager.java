package org.tron.core.db;

import com.carrotsearch.sizeof.RamUsageEstimator;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.util.Pair;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.common.storage.leveldb.LevelDbDataSourceImpl;
import org.tron.common.utils.ByteArray;
import org.tron.core.Sha256Hash;
import org.tron.core.actuator.Actuator;
import org.tron.core.actuator.ActuatorFactory;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BlockCapsule.BlockId;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.capsule.utils.BlockUtil;
import org.tron.core.config.args.Args;
import org.tron.core.config.args.GenesisBlock;
import org.tron.core.config.args.InitialWitness;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ValidateSignatureException;
import org.tron.protos.Protocol.AccountType;

public class Manager {

  private static final Logger logger = LoggerFactory.getLogger("Manager");

  private static final long BLOCK_INTERVAL_SEC = 1;
  private static final int MAX_ACTIVE_WITNESS_NUM = 21;
  private static final long TRXS_SIZE = 2_000_000; // < 2MiB
  public static final int LOOP_INTERVAL = Args.getInstance().getInitialWitness().getBlock_interval(); // millisecond

  private AccountStore accountStore;
  private TransactionStore transactionStore;
  private BlockStore blockStore;
  private UtxoStore utxoStore;
  private WitnessStore witnessStore;
  private DynamicPropertiesStore dynamicPropertiesStore;
  private BlockCapsule genesisBlock;


  private LevelDbDataSourceImpl numHashCache;
  private KhaosDatabase khaosDb;
  private BlockCapsule head;
  private RevokingStore revokingStore;
  private RevokingStore.Dialog dialog;

  public WitnessStore getWitnessStore() {
    return this.witnessStore;
  }

  private void setWitnessStore(final WitnessStore witnessStore) {
    this.witnessStore = witnessStore;
  }

  public DynamicPropertiesStore getDynamicPropertiesStore() {
    return this.dynamicPropertiesStore;
  }

  public void setDynamicPropertiesStore(final DynamicPropertiesStore dynamicPropertiesStore) {
    this.dynamicPropertiesStore = dynamicPropertiesStore;
  }

  public List<TransactionCapsule> getPendingTrxs() {
    return this.pendingTrxs;
  }


  // transaction cache
  private List<TransactionCapsule> pendingTrxs;

  private List<WitnessCapsule> wits = new ArrayList<>();

  // witness

  public List<WitnessCapsule> getWitnesses() {
    return this.wits;
  }

  public Sha256Hash getHeadBlockId() {
    return Sha256Hash.wrap(this.dynamicPropertiesStore.getLatestBlockHeaderHash());
  }

  public long getHeadBlockNum() {
    return this.head.getNum();
  }

  public void addWitness(final WitnessCapsule witnessCapsule) {
    this.wits.add(witnessCapsule);
  }

  public List<WitnessCapsule> getCurrentShuffledWitnesses() {
    return this.getWitnesses();
  }


  /**
   * get ScheduledWitness by slot.
   */
  public ByteString getScheduledWitness(final long slot) {
    final long currentSlot = this.blockStore.currentASlot() + slot;

    if (currentSlot < 0) {
      throw new RuntimeException("currentSlot should be positive.");
    }
    final List<WitnessCapsule> currentShuffledWitnesses = this.getShuffledWitnesses();
    if (CollectionUtils.isEmpty(currentShuffledWitnesses)) {
      throw new RuntimeException("ShuffledWitnesses is null.");
    }
    final int witnessIndex = (int) currentSlot % currentShuffledWitnesses.size();

    final ByteString scheduledWitness = currentShuffledWitnesses.get(witnessIndex).getAddress();
    //logger.info("scheduled_witness:" + scheduledWitness.toStringUtf8() + ",slot:" + currentSlot);

    return scheduledWitness;
  }

  public int calculateParticipationRate() {
    return 100 * this.dynamicPropertiesStore.getBlockFilledSlots().calculateFilledSlotsCount()
        / BlockFilledSlots.SLOT_NUMBER;
  }

  /**
   * get shuffled witnesses.
   */
  public List<WitnessCapsule> getShuffledWitnesses() {
    final List<WitnessCapsule> shuffleWits = this.getWitnesses();
    //Collections.shuffle(shuffleWits);
    return shuffleWits;
  }


  /**
   * all db should be init here.
   */
  public void init() {
    this.setAccountStore(AccountStore.create("account"));
    this.setTransactionStore(TransactionStore.create("trans"));
    this.setBlockStore(BlockStore.create("block"));
    this.setUtxoStore(UtxoStore.create("utxo"));
    this.setWitnessStore(WitnessStore.create("witness"));
    this.setDynamicPropertiesStore(DynamicPropertiesStore.create("properties"));

    this.numHashCache = new LevelDbDataSourceImpl(
        Args.getInstance().getOutputDirectory(), "block" + "_NUM_HASH");
    this.numHashCache.initDB();
    this.khaosDb = new KhaosDatabase("block" + "_KDB");

    this.pendingTrxs = new ArrayList<>();
    this.initGenesis();
    this.initHeadBlock(Sha256Hash.wrap(this.dynamicPropertiesStore.getLatestBlockHeaderHash()));

    revokingStore = new RevokingStore();
  }

  public BlockId getGenesisBlockId() {
    return this.genesisBlock.getBlockId();
  }

  /**
   * init genesis block.
   */
  public void initGenesis() {
    this.genesisBlock = BlockUtil.newGenesisBlockCapsule();
    if (this.containBlock(this.genesisBlock.getBlockId())) {
      Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());
    } else {
      if (this.hasBlocks()) {
        logger.error("genesis block modify, please delete database directory({}) and restart",
            Args.getInstance().getOutputDirectory());
        System.exit(1);
      } else {
        logger.info("create genesis block");
        Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());
        try {
          this.pushBlock(this.genesisBlock);
        } catch (final ValidateSignatureException e) {
          e.printStackTrace();
        }
        this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(0);
        this.dynamicPropertiesStore.saveLatestBlockHeaderHash(
            this.genesisBlock.getBlockId().getByteString());
        this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(
            this.genesisBlock.getTimeStamp());
        this.initAccount();
        this.initWitness();

      }
    }
  }

  /**
   * save account into database.
   */
  public void initAccount() {
    final Args args = Args.getInstance();
    final GenesisBlock genesisBlockArg = args.getGenesisBlock();
    genesisBlockArg.getAssets().forEach(account -> {
      final AccountCapsule accountCapsule = new AccountCapsule(account.getAccountName(),
          account.getAccountType(),
          ByteString.copyFrom(account.getAddressBytes()),
          account.getBalance());
      this.accountStore.put(account.getAddress().getBytes(), accountCapsule);
    });
  }

  private void initWitness() {
    final Args args = Args.getInstance();
    final GenesisBlock genesisBlockArg = args.getGenesisBlock();
    genesisBlockArg.getWitnesses().forEach(key -> {
      final AccountCapsule accountCapsule = new AccountCapsule(AccountType.AssetIssue,
          ByteString.copyFrom(ByteArray.fromHexString(key.getAddress())),
          Long.valueOf(0));
      final WitnessCapsule witnessCapsule = new WitnessCapsule(
          ByteString.copyFromUtf8(key.getAddress()),
          key.getVoteCount(), key.getUrl());

      this.accountStore.put(ByteArray.fromHexString(key.getAddress()), accountCapsule);
      this.witnessStore.put(ByteArray.fromHexString(key.getAddress()), witnessCapsule);
      this.wits.add(witnessCapsule);
    });
  }

  public AccountStore getAccountStore() {
    return this.accountStore;
  }

  /**
   * judge balance.
   */
  public void adjustBalance(byte[] accountAddress, long amount)
      throws BalanceInsufficientException {
    AccountCapsule account = getAccountStore().get(accountAddress);
    long balance = account.getBalance();
    if (amount == 0) {
      return;
    }
    if (amount < 0) {
      if (balance < -amount) {
        throw new BalanceInsufficientException(accountAddress + " Insufficient");
      }
    }
    account.setBalance(balance + amount);
    this.getAccountStore().put(account.getAddress().toByteArray(), account);
  }

  /**
   * push transaction into db.
   */
  public boolean pushTransactions(final TransactionCapsule trx)
      throws ValidateSignatureException, ContractValidateException, ContractExeException {
  public boolean pushTransactions(TransactionCapsule trx) {
    logger.info("push transaction");
    if (!trx.validateSignature()) {
      throw new ValidateSignatureException("trans sig validate failed");
    }

    revokingStore.buildDialog();
    if (dialog != null) {
      dialog = revokingStore.buildDialog();
    }

    try (RevokingStore.Dialog tmpDialog = revokingStore.buildDialog()) {
      processTransaction(trx);
      pendingTrxs.add(trx);
      tmpDialog.merge();
    } catch (Exception e) {
      e.printStackTrace();
    }
    getTransactionStore().dbSource.putData(trx.getTransactionId().getBytes(), trx.getData());
    return true;
  }


  /**
   * save a block.
   */
  public void pushBlock(final BlockCapsule block) throws ValidateSignatureException {
    this.khaosDb.push(block);
    //todo: check block's validity
    if (!block.generatedByMyself) {
      if (!block.validateSignature()) {
        logger.info("The siganature is not validated.");
        return;
      }

      if (!block.calcMerklerRoot().equals(block.getMerklerRoot())) {
        logger.info("The merkler root doesn't match, Calc result is " + block.calcMerklerRoot()
            + " , the headers is " + block.getMerklerRoot());
        return;
      }
      try (Dialog tmpDialog = revokingStore.buildDialog()) {
        this.processBlock(block);
        tmpDialog.commit();
      } catch (Exception e) {
        e.printStackTrace();
      }
      //todo: In some case it need to switch the branch
    }
    this.getBlockStore().dbSource.putData(block.getBlockId().getBytes(), block.getData());
    logger.info("save block, Its ID is " + block.getBlockId() + ", Its num is " + block.getNum());
    this.numHashCache.putData(ByteArray.fromLong(block.getNum()), block.getBlockId().getBytes());
    this.head = this.khaosDb.getHead();
    // blockDbDataSource.putData(blockHash, blockData);
  }


  /**
   * Get the fork branch.
   */
  public ArrayList<BlockId> getBlockChainHashesOnFork(final BlockId forkBlockHash) {
    final Pair<ArrayList<BlockCapsule>, ArrayList<BlockCapsule>> branch =
        this.khaosDb.getBranch(this.head.getBlockId(), forkBlockHash);
    return branch.getValue().stream()
        .map(blockCapsule -> blockCapsule.getBlockId())
        .collect(Collectors.toCollection(ArrayList::new));
  }

  /**
   * judge id.
   *
   * @param blockHash blockHash
   */
  public boolean containBlock(final Sha256Hash blockHash) {
    //TODO: check it from levelDB
    return this.khaosDb.containBlock(blockHash)
        || this.getBlockStore().dbSource.getData(blockHash.getBytes()) != null;
  }

  /**
   * find a block packed data by id.
   */
  public byte[] findBlockByHash(final Sha256Hash hash) {
    return this.khaosDb.containBlock(hash) ? this.khaosDb.getBlock(hash).getData()
        : this.getBlockStore().dbSource.getData(hash.getBytes());
  }

  /**
   * Get a BlockCapsule by id.
   */
  public BlockCapsule getBlockByHash(final Sha256Hash hash) {
    return this.khaosDb.containBlock(hash) ? this.khaosDb.getBlock(hash)
        : new BlockCapsule(this.getBlockStore().dbSource.getData(hash.getBytes()));
  }

  /**
   * Delete a block.
   */
  public void deleteBlock(final Sha256Hash blockHash) {
    final BlockCapsule block = this.getBlockByHash(blockHash);
    this.khaosDb.removeBlk(blockHash);
    this.getBlockStore().dbSource.deleteData(blockHash.getBytes());
    this.numHashCache.deleteData(ByteArray.fromLong(block.getNum()));
    this.head = this.khaosDb.getHead();
  }

  /**
   * judge has blocks.
   */
  public boolean hasBlocks() {
    return this.getBlockStore().dbSource.allKeys().size() > 0 || this.khaosDb.hasData();
  }

  /**
   * Process transaction.
   */
  public boolean processTransaction(final TransactionCapsule trxCap) {

    if (trxCap == null || !trxCap.validateSignature()) {
      return false;
    }
    final List<Actuator> actuatorList = ActuatorFactory.createActuator(trxCap, this);
    assert actuatorList != null;
    actuatorList.forEach(Actuator::validate);
    actuatorList.forEach(Actuator::execute);
    return true;
  }

  /**
   * Get the block id from the number.
   */
  public BlockId getBlockIdByNum(final long num) {
    final byte[] hash = this.numHashCache.getData(ByteArray.fromLong(num));
    return ArrayUtils.isEmpty(hash)
        ? this.genesisBlock.getBlockId()
        : new BlockId(Sha256Hash.wrap(hash), num);
  }

  /**
   * Get number of block by the block id.
   */
  public long getBlockNumById(final Sha256Hash hash) {
    if (this.khaosDb.containBlock(hash)) {
      return this.khaosDb.getBlock(hash).getNum();
    }

    //TODO: optimize here
    final byte[] blockByte = this.getBlockStore().dbSource.getData(hash.getBytes());
    return ArrayUtils.isNotEmpty(blockByte) ? new BlockCapsule(blockByte).getNum() : 0;
  }

  public void initHeadBlock(final Sha256Hash id) {
    this.head = this.getBlockByHash(id);
  }

  /**
   * Generate a block.
   */
  public BlockCapsule generateBlock(final WitnessCapsule witnessCapsule,
      final long when, final byte[] privateKey) {

    final long timestamp = this.dynamicPropertiesStore.getLatestBlockHeaderTimestamp();
    final long number = this.dynamicPropertiesStore.getLatestBlockHeaderNumber();
    final ByteString preHash = this.dynamicPropertiesStore.getLatestBlockHeaderHash();

    // judge create block time
    if (when < timestamp) {
      throw new IllegalArgumentException("generate block timestamp is invalid.");
    }

    long currentTrxSize = 0;
    long postponedTrxCount = 0;

    final BlockCapsule blockCapsule = new BlockCapsule(number + 1, preHash, when,
        witnessCapsule.getAddress());
    try {
      dialog.close();
    } catch (Exception e) {

    }
    dialog = revokingStore.buildDialog();

    Iterator iterator = pendingTrxs.iterator();
    while (iterator.hasNext()) {
      TransactionCapsule trx = (TransactionCapsule) iterator.next();
      currentTrxSize += RamUsageEstimator.sizeOf(trx);
      // judge block size
      if (currentTrxSize > TRXS_SIZE) {
        postponedTrxCount++;
        continue;
      }
      // apply transaction
      try {
        try (Dialog tmpDialog = revokingStore.buildDialog()) {
          processTransaction(trx);
          tmpDialog.merge();
        } catch (Exception e) {
          e.printStackTrace();
        }
        // push into block
        blockCapsule.addTransaction(trx);
        iterator.remove();
      } catch (ContractExeException e) {
        logger.info("contract not processed during execute");
        e.printStackTrace();
      } catch (ContractValidateException e) {
        logger.info("contract not processed during validate");
        e.printStackTrace();
      }
    }
    try {
      dialog.close();
    } catch (Exception e) {
      e.printStackTrace();
    }

    if (postponedTrxCount > 0) {
      logger.info("{} transactions over the block size limit", postponedTrxCount);
    }

    logger.info("postponedTrxCount[" + postponedTrxCount + "],TrxLeft[" + pendingTrxs.size() + "]");

    blockCapsule.setMerklerRoot();
    blockCapsule.sign(privateKey);
    blockCapsule.generatedByMyself = true;
    this.pushBlock(blockCapsule);
    this.dynamicPropertiesStore
        .saveLatestBlockHeaderHash(blockCapsule.getBlockId().getByteString());
    this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(blockCapsule.getNum());
    this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(blockCapsule.getTimeStamp());
    return blockCapsule;
  }

  private void setAccountStore(final AccountStore accountStore) {
    this.accountStore = accountStore;
  }

  public TransactionStore getTransactionStore() {
    return this.transactionStore;
  }

  private void setTransactionStore(final TransactionStore transactionStore) {
    this.transactionStore = transactionStore;
  }

  public BlockStore getBlockStore() {
    return this.blockStore;
  }

  private void setBlockStore(final BlockStore blockStore) {
    this.blockStore = blockStore;
  }

  public UtxoStore getUtxoStore() {
    return this.utxoStore;
  }

  private void setUtxoStore(final UtxoStore utxoStore) {
    this.utxoStore = utxoStore;
  }

  /**
   * process block.
   */
  public void processBlock(BlockCapsule block) throws ValidateSignatureException {
    for (TransactionCapsule transactionCapsule : block.getTransactions()) {
      try {
        processTransaction(transactionCapsule);
      } catch (ContractExeException e) {
        e.printStackTrace();
      } catch (ContractValidateException e) {
        e.printStackTrace();
      }
      this.updateDynamicProperties(block);
      this.updateSignedWitness(block);
      if (this.dynamicPropertiesStore.getNextMaintenanceTime().getMillis() <= block
          .getTimeStamp()) {
        this.processMaintenance();
      }
    }
  }

  private void updateDynamicProperties(final BlockCapsule block) {

  }

  private void processMaintenance() {
    this.updateWitness();
    this.dynamicPropertiesStore.updateMaintenanceTime();
  }


  public void updateSignedWitness(BlockCapsule block) {
    //TODO: add verification
    WitnessCapsule witnessCapsule = witnessStore
        .get(block.getInstance().getBlockHeader().getRawData().getWitnessAddress().toByteArray());

    long latestSlotNum = 0L;

//    dynamicPropertiesStore.current_aslot + getSlotAtTime(new DateTime(block.getTimeStamp()));

    witnessCapsule.getInstance().toBuilder().setLatestBlockNum(block.getNum())
        .setLatestSlotNum(latestSlotNum)
        .build();

    processFee();
  }

  private void processFee() {

  }

  private long blockInterval() {
    return LOOP_INTERVAL; // millisecond todo getFromDb
  }

  public long getSlotAtTime(DateTime when) {
    DateTime firstSlotTime = getSlotTime(1);
    if (when.isBefore(firstSlotTime)) {
      return 0;
    }
    return (when.getMillis() - firstSlotTime.getMillis()) / blockInterval() + 1;
  }


  public DateTime getSlotTime(long slotNum) {
    if (slotNum == 0) {
      return DateTime.now();
    }
    long interval = blockInterval();
    BlockStore blockStore = getBlockStore();
    DateTime genesisTime = blockStore.getGenesisTime();
    if (blockStore.getHeadBlockNum() == 0) {
      return genesisTime.plus(slotNum * interval);
    }

    if (lastHeadBlockIsMaintenance()) {
      slotNum += getSkipSlotInMaintenance();
    }

    DateTime headSlotTime = blockStore.getHeadBlockTime();

    //align slot time
    headSlotTime = headSlotTime
        .minus((headSlotTime.getMillis() - genesisTime.getMillis()) % interval);

    return headSlotTime.plus(interval * slotNum);
  }


  private boolean lastHeadBlockIsMaintenance() {
    return getDynamicPropertiesStore().getStateFlag() == 1;
  }

  private long getSkipSlotInMaintenance() {
    return 0;
  }

  /**
   * update witness.
   */
  public void updateWitness() {
    final Map<ByteString, Long> countWitness = Maps.newHashMap();
    final List<AccountCapsule> accountList = this.accountStore.getAllAccounts();
    logger.info("there is account List size is {}", accountList.size());
    accountList.forEach(account -> {
      logger.info("there is account ,account address is {}", account.getAddress().toStringUtf8());

      Optional<Long> sum = account.getVotesList().stream().map(vote -> vote.getVoteCount())
          .reduce((a, b) -> a + b);
      if (sum.isPresent()) {
        if (sum.get() <= account.getShare()) {
          account.getVotesList().forEach(vote -> {
            //TODO validate witness //active_witness
            if (countWitness.containsKey(vote.getVoteAddress())) {
              countWitness.put(vote.getVoteAddress(),
                  countWitness.get(vote.getVoteAddress()) + vote.getVoteCount());
            } else {
              countWitness.put(vote.getVoteAddress(), vote.getVoteCount());
            }
          });
        } else {
          logger.info(
              "account" + account.getAddress() + ",share[" + account.getShare() + "] > voteSum["
                  + sum.get() + "]");
        }
      }
    });
    final List<WitnessCapsule> witnessCapsuleList = Lists.newArrayList();
    logger.info("countWitnessMap size is {}", countWitness.keySet().size());
    countWitness.forEach((address, voteCount) -> {
      final WitnessCapsule witnessCapsule = this.witnessStore.getWitness(address);
      if (null == witnessCapsule) {
        logger.warn("witnessCapsule is null.address is {}", address);
        return;
      }

      ByteString witnessAddress = witnessCapsule.getInstance().getAddress();
      AccountCapsule witnessAccountCapsule = accountStore.get(witnessAddress.toByteArray());
      if (witnessAccountCapsule == null) {
        logger.warn("witnessAccount[" + witnessAddress + "] not exists");
      }

      if (witnessAccountCapsule.getBalance() < WitnessCapsule.MIN_BALANCE) {
        logger.warn("witnessAccount[" + witnessAddress + "] has balance[" + witnessAccountCapsule
            .getBalance() + "] < MIN_BALANCE[" + WitnessCapsule.MIN_BALANCE + "]");
      }

      witnessCapsule.setVoteCount(witnessCapsule.getVoteCount() + voteCount);
      witnessCapsuleList.add(witnessCapsule);
      this.witnessStore.putWitness(witnessCapsule);
      logger.info("address is {}  ,countVote is {}", witnessCapsule.getAddress().toStringUtf8(),
          witnessCapsule.getVoteCount());
    });
    witnessCapsuleList.sort((a, b) -> {
      return (int) (a.getVoteCount() - b.getVoteCount());
    });
    if (this.wits.size() > MAX_ACTIVE_WITNESS_NUM) {
      this.wits = witnessCapsuleList.subList(0, MAX_ACTIVE_WITNESS_NUM);
    }
  }
}
