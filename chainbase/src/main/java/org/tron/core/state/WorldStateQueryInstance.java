package org.tron.core.state;

import static org.tron.core.state.WorldStateCallBack.fix32;

import com.google.common.primitives.Longs;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.Getter;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.DecodeUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.BytesCapsule;
import org.tron.core.capsule.CodeCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ContractStateCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.capsule.StorageRowCapsule;
import org.tron.core.capsule.VotesCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.exception.ItemNotFoundException;
import org.tron.core.exception.StoreException;
import org.tron.core.state.trie.TrieImpl2;
import org.tron.protos.Protocol;

public class WorldStateQueryInstance {

  private final TrieImpl2 trieImpl;

  @Getter
  private final Bytes32 rootHash;

  public static final byte[] DELETE = UInt256.ZERO.toArray();
  public static final int ADDRESS_SIZE = DecodeUtil.ADDRESS_SIZE >> 1;
  public static final Bytes MAX_ASSET_ID = Bytes.ofUnsignedLong(Long.MAX_VALUE);
  public static final Bytes MIN_ASSET_ID = Bytes.ofUnsignedLong(0);

  private final WorldStateGenesis worldStateGenesis;
  private final ChainBaseManager chainBaseManager;

  public WorldStateQueryInstance(Bytes32 rootHash, ChainBaseManager chainBaseManager) {
    this.rootHash = rootHash;
    this.trieImpl = new TrieImpl2(chainBaseManager.getMerkleStorage(), rootHash);
    this.worldStateGenesis = chainBaseManager.getWorldStateGenesis();
    this.chainBaseManager = chainBaseManager;
  }

  private byte[] get(StateType type, byte[] key) {
    byte[] encodeKey = StateType.encodeKey(type, key);
    Bytes value = trieImpl.get(encodeKey);

    if (Objects.nonNull(value)) {
      if (Objects.equals(value, UInt256.ZERO)) {
        return null;
      }
      return value.toArrayUnsafe();
    }
    return worldStateGenesis.get(type, key);
  }

  public AccountCapsule getAccount(byte[] address) {
    byte[] value = get(StateType.Account, address);
    AccountCapsule accountCapsule = null;
    if (Objects.nonNull(value)) {
      accountCapsule = new AccountCapsule(value);
      accountCapsule.setRoot(rootHash);
    }
    return accountCapsule;
  }

  public Long getAccountAsset(byte[] address, long tokenId) {
    byte[] key = com.google.common.primitives.Bytes.concat(address,
            Bytes.ofUnsignedLong(tokenId).toArray());
    byte[] encodeKey = StateType.encodeKey(StateType.AccountAsset, key);
    Bytes value = trieImpl.get(fix32(encodeKey));
    if (Objects.nonNull(value)) {
      if (Objects.equals(value, UInt256.ZERO)) {
        return null;
      }
      return value.toLong();
    }
    byte[] v = worldStateGenesis.get(StateType.AccountAsset,
            com.google.common.primitives.Bytes.concat(address,
            Long.toString(tokenId).getBytes()));
    return Objects.nonNull(v) ? Longs.fromByteArray(v) : null;
  }

  public long getAccountAsset(Protocol.Account account, long tokenId) {
    Long amount = getAccountAsset(account.getAddress().toByteArray(), tokenId);
    return amount == null ? 0 : amount;
  }

  public boolean hasAssetV2(Protocol.Account account, long tokenId) {
    return getAccountAsset(account.getAddress().toByteArray(), tokenId) != null;
  }

  public Map<String, Long>  importAllAsset(Protocol.Account account) {
    Map<String, Long> assets = new TreeMap<>();
    Map<Bytes, Bytes> genesis = worldStateGenesis.prefixQuery(StateType.AccountAsset,
            account.getAddress().toByteArray());
    genesis.forEach((k, v) -> assets.put(
            ByteArray.toStr(k.slice(ADDRESS_SIZE).toArray()),
            v.toLong())
    );
    Bytes address =  Bytes.of(account.getAddress().toByteArray());
    Bytes32 min = fix32(Bytes.wrap(Bytes.of(StateType.AccountAsset.value()), address,
            MIN_ASSET_ID));

    Bytes32 max = fix32(Bytes.wrap(Bytes.of(StateType.AccountAsset.value()), address,
            MAX_ASSET_ID));
    TreeMap<Bytes32, Bytes> state = trieImpl.entriesFrom(min, max);
    // remove asset is deleted
    state.entrySet().stream()
            .filter(e -> !Objects.equals(e.getValue(), UInt256.ZERO))
            .forEach(e -> assets.put(
                    String.valueOf(
                            e.getKey().slice(Byte.BYTES + ADDRESS_SIZE, Long.BYTES).toLong()),
                    e.getValue().toLong()));
    // remove asset = 0
    assets.entrySet().removeIf(e -> e.getValue() <= 0);
    return assets;
  }

  // contract
  public ContractCapsule getContract(byte[] address) {
    byte[] value = get(StateType.Contract, address);
    return Objects.nonNull(value) ? new ContractCapsule(value) : null;
  }

  public ContractStateCapsule getContractState(byte[] address) {
    byte[] value = get(StateType.ContractState, address);
    return Objects.nonNull(value) ? new ContractStateCapsule(value) : null;
  }

  public CodeCapsule getCode(byte[] address) {
    byte[] value = get(StateType.Code, address);
    return Objects.nonNull(value) ? new CodeCapsule(value) : null;
  }


  // never return null
  public StorageRowCapsule getStorageRow(byte[] key) {
    byte[] value = get(StateType.StorageRow, key);
    StorageRowCapsule storageRowCapsule = new StorageRowCapsule(value);
    storageRowCapsule.setRowKey(key);
    return storageRowCapsule;
  }

  // asset
  public AssetIssueCapsule getAssetIssue(byte[] tokenId) {
    byte[] value = get(StateType.AssetIssue, tokenId);
    return Objects.nonNull(value) ? new AssetIssueCapsule(value) : null;
  }

  // witness
  public WitnessCapsule getWitness(byte[] address) {
    byte[] value = get(StateType.Witness, address);
    return Objects.nonNull(value) ? new WitnessCapsule(value) : null;
  }

  // delegate
  public DelegatedResourceCapsule getDelegatedResource(byte[] key) {
    byte[] value = get(StateType.DelegatedResource, key);
    return Objects.nonNull(value) ? new DelegatedResourceCapsule(value) : null;
  }

  public BytesCapsule getDelegation(byte[] key) {
    byte[] value = get(StateType.Delegation, key);
    return Objects.nonNull(value) ? new BytesCapsule(value) : null;
  }

  // TODO prefix query
  public DelegatedResourceAccountIndexCapsule getDelegatedResourceAccountIndex(byte[] key) {
    byte[] value = get(StateType.DelegatedResourceAccountIndex, key);
    return Objects.nonNull(value) ? new DelegatedResourceAccountIndexCapsule(value) : null;
  }

  // vote
  public VotesCapsule getVotes(byte[] address) {
    byte[] value = get(StateType.Votes, address);
    return Objects.nonNull(value) ? new VotesCapsule(value) : null;
  }

  // properties
  public BytesCapsule getDynamicProperty(byte[] key) throws ItemNotFoundException {
    BytesCapsule value = getUncheckedDynamicProperty(key);
    if (Objects.nonNull(value)) {
      return value;
    } else {
      throw new ItemNotFoundException();
    }
  }

  public BytesCapsule getUncheckedDynamicProperty(byte[] key) {
    byte[] value = get(StateType.Properties, key);
    if (Objects.nonNull(value)) {
      return new BytesCapsule(value);
    }
    return null;
  }

  // getBlockByNum
  public BlockCapsule getBlockByNum(long num) throws StoreException {
    return chainBaseManager.getBlockByNum(num);
  }

}
