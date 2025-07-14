package it.mattiolservices.mantivpn.alert.info;

import it.mattiolservices.mantivpn.antivpn.core.IPCheckResult;
import lombok.Builder;
import lombok.With;

import java.time.LocalDateTime;

@Builder
@With
public record AlertInfo(
        String username,
        String playerIP,
        IPCheckResult result,
        LocalDateTime timestamp
) {}