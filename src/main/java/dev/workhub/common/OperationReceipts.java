package dev.workhub.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class OperationReceipts {
    private final OperationReceiptRepository repository;

    public OperationReceipts(OperationReceiptRepository repository) {
        this.repository = repository;
    }

    // Authorization and the caller's project/creator lock must precede receipt access.
    public UUID find(UUID actor, String key, String hash) {
        Inputs.text(key, "Idempotency-Key", 128);
        var receipt = repository.find(actor, key);
        if (receipt.isEmpty()) return null;
        if (!receipt.get().hash().equals(hash)) throw conflict();
        return receipt.get().id();
    }

    public void save(UUID actor, String key, UUID project, String hash, UUID result) {
        try {
            repository.insert(actor, key, project, hash, result);
        } catch (DuplicateKeyException error) {
            throw conflict();
        } // Cross-project key race rollsa back this whole transaction.
    }

    private static ApiException conflict() {
        return new ApiException(
                409, "IDEMPOTENCY_CONFLICT", "Key was used for a different request");
    }
}
