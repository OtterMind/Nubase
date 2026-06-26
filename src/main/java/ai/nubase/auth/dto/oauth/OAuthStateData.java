package ai.nubase.auth.dto.oauth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * OAuth State Data
 * Stores context information for OAuth flow state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthStateData implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * OAuth provider name (google, github, etc.)
     */
    private String provider;

    /**
     * API key for tenant authentication
     */
    private String apikey;

    /**
     * Redirect URL after OAuth callback
     */
    private String redirectTo;

    /**
     * OAuth provider callback URL used when the flow was initiated. The provider requires the
     * token exchange redirect_uri to exactly match the original authorization redirect_uri.
     */
    private String callbackUrl;

    /**
     * Timestamp when state was created
     */
    private Long createdAt;

    /**
     * PKCE code challenge (optional). When present, the callback issues a one-time
     * auth code (via auth.flow_state) instead of returning tokens directly.
     */
    private String codeChallenge;

    /**
     * PKCE code challenge method ('s256' | 'plain').
     */
    private String codeChallengeMethod;

    /**
     * When set, the OAuth callback LINKS the resolved provider identity to this existing user
     * (manual identity linking) instead of finding-or-creating a user.
     */
    private String linkUserId;
}
