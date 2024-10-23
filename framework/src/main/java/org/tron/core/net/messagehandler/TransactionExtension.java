package org.tron.core.net.messagehandler;

import org.tron.trident.proto.Chain.Transaction;

public class TransactionExtension {
	public Transaction transaction;
	public TransanctionType eType;
	public int nIndex;

	public TransactionExtension(Transaction transaction, TransanctionType type,  int nIndex) {
		this.transaction = transaction;
		this.eType = type;
		this.nIndex = nIndex;
	}
}
