/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2014 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http.servlet;

import com.novell.ldapchai.exception.ChaiUnavailableException;
import password.pwm.PwmConstants;
import password.pwm.Validator;
import password.pwm.bean.UserIdentity;
import password.pwm.config.Configuration;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.http.PwmRequest;
import password.pwm.ldap.auth.AuthenticationType;
import password.pwm.ldap.auth.SessionAuthenticator;
import password.pwm.util.PasswordData;
import password.pwm.util.logging.PwmLogger;
import password.pwm.ws.server.RestResultBean;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * User interaction servlet for form-based authentication.   Depending on how PWM is deployed,
 * users may or may not ever visit this servlet.   Generally, if PWM is behind iChain, or some
 * other SSO enabler using HTTP BASIC authentication, this form will not be invoked.
 *
 * @author Jason D. Rivard
 */
public class LoginServlet extends PwmServlet {
// ------------------------------ FIELDS ------------------------------

    private static final PwmLogger LOGGER = PwmLogger.getLogger(LoginServlet.class.getName());

    public enum LoginServletAction implements ProcessAction {
        login(HttpMethod.POST),
        restLogin(HttpMethod.POST),

        ;

        private final HttpMethod method;

        LoginServletAction(HttpMethod method)
        {
            this.method = method;
        }

        public Collection<HttpMethod> permittedMethods()
        {
            return Collections.singletonList(method);
        }
    }

    protected LoginServletAction readProcessAction(final PwmRequest request)
            throws PwmUnrecoverableException
    {
        try {
            return LoginServletAction.valueOf(request.readParameterAsString(PwmConstants.PARAM_ACTION_REQUEST));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }


    public void processAction(
            final PwmRequest pwmRequest
    )
            throws ServletException, IOException, ChaiUnavailableException, PwmUnrecoverableException
    {
        final boolean passwordOnly = pwmRequest.getPwmSession().getSessionStateBean().isAuthenticated() &&
                pwmRequest.getPwmSession().getLoginInfoBean().getAuthenticationType() == AuthenticationType.AUTH_WITHOUT_PASSWORD;

        final LoginServletAction action = readProcessAction(pwmRequest);

        if (action != null) {
            Validator.validatePwmFormID(pwmRequest.getHttpServletRequest());

            switch (action) {
                case login:
                    processLogin(pwmRequest, passwordOnly);
                    break;

                case restLogin:
                    processRestLogin(pwmRequest, passwordOnly);
                    break;
            }

            return;
        }

        forwardToJSP(pwmRequest, passwordOnly);
    }

    private void processLogin(final PwmRequest pwmRequest, final boolean passwordOnly)
            throws PwmUnrecoverableException, ServletException, IOException, ChaiUnavailableException
    {
        final String username = pwmRequest.readParameterAsString("username");
        final PasswordData password = pwmRequest.readParameterAsPassword("password");
        final String context = pwmRequest.readParameterAsString(PwmConstants.PARAM_CONTEXT);
        final String ldapProfile = pwmRequest.readParameterAsString(PwmConstants.PARAM_LDAP_PROFILE);

        try {
            handleLoginRequest(pwmRequest, username, password, context, ldapProfile, passwordOnly);
        } catch (PwmOperationalException e) {
            pwmRequest.setResponseError(e.getErrorInformation());
            forwardToJSP(pwmRequest, passwordOnly);
            return;
        }

        // login has succeeded
        pwmRequest.sendRedirectToPreLoginUrl();
    }

    private void processRestLogin(final PwmRequest pwmRequest, final boolean passwordOnly)
            throws PwmUnrecoverableException, ServletException, IOException, ChaiUnavailableException
    {
        final Map<String, String> valueMap = pwmRequest.readBodyAsJsonStringMap();

        if (valueMap == null || valueMap.isEmpty()) {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_MISSING_PARAMETER,"missing json request body");
            pwmRequest.outputJsonResult(RestResultBean.fromError(errorInformation, pwmRequest));
            return;
        }

        final Configuration config = pwmRequest.getConfig();
        final String username = Validator.sanitizeInputValue(config, valueMap.get("username"), 1024);
        final PasswordData password = new PasswordData(Validator.sanitizeInputValue(config, valueMap.get("password"), 1024));
        final String context = Validator.sanitizeInputValue(config, valueMap.get(PwmConstants.PARAM_CONTEXT), 1024);
        final String ldapProfile = Validator.sanitizeInputValue(config, valueMap.get(PwmConstants.PARAM_LDAP_PROFILE),
                1024);

        try {
            handleLoginRequest(pwmRequest, username, password, context, ldapProfile, passwordOnly);
        } catch (PwmOperationalException e) {
            pwmRequest.outputJsonResult(RestResultBean.fromError(e.getErrorInformation(), pwmRequest));
            return;
        }

        // login has succeeded
        final RestResultBean restResultBean = new RestResultBean();
        final HashMap<String,String> resultMap = new HashMap<>(Collections.singletonMap("nextURL", pwmRequest.determinePostLoginUrl()));
        restResultBean.setData(resultMap);
        LOGGER.debug(pwmRequest, "rest login succeeded");
        pwmRequest.outputJsonResult(restResultBean);
    }

    private void handleLoginRequest(
            final PwmRequest pwmRequest,
            final String username,
            final PasswordData password,
            final String context,
            final String ldapProfile,
            final boolean passwordOnly
    )
            throws PwmOperationalException, ChaiUnavailableException, PwmUnrecoverableException, IOException, ServletException
    {
        if (!passwordOnly && (username == null || username.isEmpty())) {
            throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_MISSING_PARAMETER,"missing username parameter"));
        }

        if (password == null) {
            throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_MISSING_PARAMETER,"missing password parameter"));
        }

        final SessionAuthenticator sessionAuthenticator = new SessionAuthenticator(pwmRequest.getPwmApplication(), pwmRequest.getPwmSession());
        if (passwordOnly) {
            final UserIdentity userIdentity = pwmRequest.getPwmSession().getUserInfoBean().getUserIdentity();
            sessionAuthenticator.authenticateUser(userIdentity, password);
        } else {
            sessionAuthenticator.searchAndAuthenticateUser(username, password, context, ldapProfile);
        }

        // recycle the session to prevent session fixation attack.
        pwmRequest.recycleSessions();
    }

    private void forwardToJSP(
            final PwmRequest pwmRequest,
            final boolean passwordOnly
    )
            throws IOException, ServletException, PwmUnrecoverableException
    {
        final PwmConstants.JSP_URL url = passwordOnly ? PwmConstants.JSP_URL.LOGIN_PW_ONLY : PwmConstants.JSP_URL.LOGIN;
        pwmRequest.forwardToJsp(url);
    }
}
