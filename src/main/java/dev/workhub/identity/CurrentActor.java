package dev.workhub.identity;

import dev.workhub.common.ApiException;

import org.springframework.stereotype.Component;

import java.security.Principal;

@Component
public class CurrentActor {
    private final IdentityRepository identities;

    public CurrentActor(IdentityRepository identities) {
        this.identities = identities;
    }

    public ActorContext from(Principal principal) {
        if (principal == null) throw ApiException.forbidden();
        var user =
                identities
                        .findByLogin(principal.getName())
                        .filter(IdentityRepository.User::active)
                        .orElseThrow(ApiException::forbidden);
        return new ActorContext(user.id());
    }
}
