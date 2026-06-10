package ai.nubase.auth.service;

import ai.nubase.common.config.AuthConfig;
import ai.nubase.auth.entity.User;
import ai.nubase.common.context.MultiTenancyContext;
import ai.nubase.platform.mail.DynamicJavaMailSender;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final AuthConfig authConfig;
    private final DynamicJavaMailSender dynamicMailSender;
    private final EmailTemplateService emailTemplateService;

    private static final String htmlTemplate = """
            <!DOCTYPE html>
            <html lang="en">
              <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Confirm Your Email</title>
              </head>
              <body>
                <div>
                  <style type="text/css">
                    body {
                      height: 100%;
                      margin: 0;
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                        Oxygen, Ubuntu, Cantarell, sans-serif;
                    }
                    .container {
                      margin: 0 auto;
                    }
                    .header h1 {
                      margin: 0;
                      font-size: 24px;
                      font-weight: 600;
                    }
                    .content {
                      padding: 14px 0px;
                    }
                    .content p {
                      font-size: 16px;
                      color: #4a5568;
                      line-height: 1.6;
                    }
                    .content .email {
                      font-weight: 600;
                      color: #171717;
                    }
                    .btn {
                      display: inline-block;
                      padding: 14px 32px;
                      background: #171717;
                      color: white;
                      text-decoration: none;
                      border-radius: 8px;
                      font-size: 16px;
                      font-weight: 500;
                      transition: background 0.2s;
                    }
                    .btn:hover {
                      background: #2c5282;
                    }
                    .footer p {
                      margin: 0;
                      font-size: 14px;
                      color: #737373;
                    }
                    @media only screen and (max-width: 600px) {
                      .container {
                        width: 100% !important;
                        border-radius: 0;
                      }
                      table {
                        height: 100%;
                      }
                    }
                  </style>
                  <table
                    width="100%"
                    border="0"
                    cellpadding="0"
                    cellspacing="0"
                    style="padding: 30px 20px"
                  >
                    <tr>
                      <td align="center">
                        <table
                          class="container"
                          border="0"
                          cellpadding="0"
                          cellspacing="0"
                        >
                          <tr>
                            <td class="header">
                              <h1 style="margin: 0; font-size: 24px; font-weight: 600">
                                Confirm your email address
                              </h1>
                            </td>
                          </tr>
                          <tr>
                            <td class="content">
                              <p
                                style="
                                  margin: 0 0 8px;
                                  font-size: 16px;
                                  color: #4a5568;
                                  line-height: 1.6;
                                "
                              >
                                Thanks for signing up for
                                <strong style="color: #2d3748">{{appName}}</strong>! We're excited to have you.
                              </p>
                              <p
                                style="
                                  margin: 0 0 8px;
                                  font-size: 16px;
                                  color: #4a5568;
                                  line-height: 1.6;
                                "
                              >
                                Please verify your email address (<span
                                  class="email"
                                  style="font-weight: 600; color: #171717"
                                  >{{email}}</span
                                >) to activate your account. Click the button below to confirm.
                              </p>
                              <p>
                                <a
                                  href="{{link}}"
                                  class="btn"
                                  style="
                                    display: inline-block;
                                    padding: 8px 22px;
                                    background: #171717;
                                    color: #FFFFFF;
                                    text-decoration: none;
                                    border-radius: 6px;
                                    font-size: 14px;
                                    font-weight: 500;
                                  "
                                  rel="noopener noreferrer"
                                  target="_blank"
                                  >Confirm Email</a
                                >
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td class="footer">
                              <p style="margin: 0; font-size: 14px; color: #737373">
                                If you didn't create an account, no action is required. For your security, this link will expire.
                              </p>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </div>
              </body>
            </html>
            """;

    private String siteName() {
        String appCode = MultiTenancyContext.getAppCode();
        return MultiTenancyContext.getAppName() != null ? MultiTenancyContext.getAppName() : appCode;
    }

    private String baseDomain() {
        return authConfig.getApp().getDomain(MultiTenancyContext.getAppCode());
    }

    /** URL-encode a query-parameter value so emails/tokens with &, +, spaces, # don't corrupt the link. */
    private static String enc(String v) {
        return v == null ? "" : URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    /**
     * Send confirmation email
     */
    public void sendConfirmationEmail(User user, String token, String redirectTo) {
        String confirmUrl = baseDomain() + "/auth/v1/verify?token=" + enc(token) + "&type=signup&email="
                + enc(user.getEmail()) + "&apikey=" + enc(MultiTenancyContext.getContext().getApikey());
        if (StringUtils.isNotBlank(redirectTo)) {
            confirmUrl += "&redirect_to=" + enc(redirectTo);
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("ConfirmationURL", confirmUrl);
        vars.put("Email", user.getEmail());
        vars.put("SiteURL", siteName());
        send(user.getEmail(), EmailTemplateService.CONFIRMATION, vars);
    }

    /**
     * Send password recovery email
     */
    public void sendRecoveryEmail(User user, String token) {
        String resetUrl = baseDomain() + "/auth/v1/verify?token=" + enc(token) + "&type=recovery&email=" + enc(user.getEmail());
        Map<String, String> vars = new HashMap<>();
        vars.put("ConfirmationURL", resetUrl);
        vars.put("Email", user.getEmail());
        vars.put("SiteURL", siteName());
        send(user.getEmail(), EmailTemplateService.RECOVERY, vars);
    }

    /**
     * Send email change confirmation
     */
    public void sendEmailChangeConfirmation(User user, String newEmail, String token) {
        String confirmUrl = baseDomain() + "/auth/v1/verify?token=" + enc(token) + "&type=email_change&email=" + enc(newEmail);
        Map<String, String> vars = new HashMap<>();
        vars.put("ConfirmationURL", confirmUrl);
        vars.put("Email", user.getEmail());
        vars.put("NewEmail", newEmail);
        vars.put("SiteURL", siteName());
        send(newEmail, EmailTemplateService.EMAIL_CHANGE, vars);
    }

    /**
     * Send a passwordless magic-link email. The link verifies a one-time token and
     * establishes a session; the email also shows the numeric OTP code as a fallback.
     */
    public void sendMagicLinkEmail(User user, String token, String otpCode, String redirectTo) {
        String link = baseDomain() + "/auth/v1/verify?token=" + enc(token) + "&type=magiclink&email="
                + enc(user.getEmail()) + "&apikey=" + enc(MultiTenancyContext.getContext().getApikey());
        if (StringUtils.isNotBlank(redirectTo)) {
            link += "&redirect_to=" + enc(redirectTo);
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("ConfirmationURL", link);
        vars.put("Token", otpCode);
        vars.put("Email", user.getEmail());
        vars.put("SiteURL", siteName());
        send(user.getEmail(), EmailTemplateService.MAGIC_LINK, vars);
    }

    /**
     * Send a one-time login code (email OTP). Reuses the magic-link template (code is the Token).
     */
    public void sendOtpEmail(User user, String otpCode) {
        Map<String, String> vars = new HashMap<>();
        vars.put("Token", otpCode);
        vars.put("Email", user.getEmail());
        vars.put("SiteURL", siteName());
        vars.put("ConfirmationURL", "");
        send(user.getEmail(), EmailTemplateService.MAGIC_LINK, vars);
    }

    /**
     * Send a reauthentication nonce (6-digit code) for sensitive operations.
     */
    public void sendReauthenticationEmail(User user, String nonce) {
        Map<String, String> vars = new HashMap<>();
        vars.put("Token", nonce);
        vars.put("Email", user.getEmail());
        send(user.getEmail(), EmailTemplateService.REAUTHENTICATION, vars);
    }

    /**
     * Send invitation email for admin-invited users
     */
    public void sendInvitationEmail(User user, String token, String redirectTo) {
        // Must hit the /auth/v1/verify endpoint (like confirmation/recovery) — not the bare domain,
        // which just lands on the marketing homepage and can't accept the invite.
        String inviteUrl = baseDomain() + "/auth/v1/verify?token=" + enc(token) + "&type=invite&email="
                + enc(user.getEmail()) + "&apikey=" + enc(MultiTenancyContext.getContext().getApikey());
        if (StringUtils.isNotBlank(redirectTo)) {
            inviteUrl += "&redirect_to=" + enc(redirectTo);
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("ConfirmationURL", inviteUrl);
        vars.put("Email", user.getEmail());
        vars.put("SiteURL", siteName());
        send(user.getEmail(), EmailTemplateService.INVITE, vars);
    }

    /** Render the effective template for {@code type} and send it. */
    private void send(String to, String type, Map<String, String> vars) {
        EmailTemplateService.Rendered rendered = emailTemplateService.render(type, vars);
        sendEmail(to, rendered.subject(), rendered.body());
    }

    /**
     * Generic email sending method
     */
    private void sendEmail(String to, String subject, String body) {
        try {
            // 1. Create the MimeMessage
            MimeMessage message = mailSender.createMimeMessage();
            // 2. Use Helper; the second parameter true enables multipart support (attachments/HTML)
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(dynamicMailSender.resolveFromAddress(authConfig.getEmail().getFromAddress()));
            helper.setTo(to);
            helper.setSubject(subject);
            // 3. Key point: the second parameter true indicates the content is HTML
            helper.setText(body, true);
            mailSender.send(message);
            log.info("Email sent to: {}", to);
        } catch (Exception e) {
            log.error("Failed to send email to: {}", to, e);
        }
    }

}
