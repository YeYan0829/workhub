package dev.workhub.identity;

import dev.workhub.common.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SessionService {
    private final IdentityRepository identities;

    public SessionService(IdentityRepository identities) {
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public List<IdentityRepository.WorkspaceView> workspaces(ActorContext actor) {
        if (!identities.activeUser(actor.userId(), false)) throw ApiException.forbidden();
        return identities.workspaces(actor.userId());
    }
}
