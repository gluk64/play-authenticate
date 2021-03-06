package com.feth.play.module.pa.providers.oauth1;

import com.fasterxml.jackson.databind.JsonNode;
import com.feth.play.module.pa.PlayAuthenticate;
import com.feth.play.module.pa.controllers.Authenticate;
import com.feth.play.module.pa.exceptions.AccessDeniedException;
import com.feth.play.module.pa.exceptions.AccessTokenException;
import com.feth.play.module.pa.exceptions.AuthException;
import com.feth.play.module.pa.exceptions.RedirectUriMismatch;
import com.feth.play.module.pa.providers.ext.ExternalAuthProvider;
import com.feth.play.module.pa.user.AuthUserIdentity;

import oauth.signpost.exception.OAuthException;
import play.Application;
import play.Configuration;
import play.Logger;
import play.api.libs.json.JsValue;
import play.api.libs.oauth.ConsumerKey;
import play.api.libs.oauth.OAuth;
import play.api.libs.oauth.OAuthCalculator;
import play.api.libs.oauth.RequestToken;
import play.api.libs.oauth.ServiceInfo;
import play.api.libs.ws.Response;
import play.api.libs.ws.WS;
import play.libs.Crypto;
import play.libs.Json;
import play.mvc.Http.Context;
import play.mvc.Http.Request;
import play.mvc.Http.Session;
import scala.concurrent.Future;
import scala.util.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class OAuth1AuthProvider<U extends AuthUserIdentity, I extends OAuth1AuthInfo>
		extends ExternalAuthProvider {

	private static final String STATE_TOKEN = "pa.oauth1.rtoken";

    private static final long STATE_TIMEOUT = 15*60*1000; // (ms) = 15 min

	public OAuth1AuthProvider(final Application app) {
		super(app);
	}

	protected abstract I buildInfo(final RequestToken rtoken)
			throws AccessTokenException;

	@Override
	protected List<String> neededSettingKeys() {
		final List<String> ret = new ArrayList<String>();
		ret.addAll(super.neededSettingKeys());
		ret.add(SettingKeys.ACCESS_TOKEN_URL);
		ret.add(SettingKeys.AUTHORIZATION_URL);
		ret.add(SettingKeys.REQUEST_TOKEN_URL);
		ret.add(SettingKeys.CONSUMER_KEY);
		ret.add(SettingKeys.CONSUMER_SECRET);
		return ret;
	}

	public static abstract class SettingKeys {
		public static final String REQUEST_TOKEN_URL = "requestTokenUrl";
		public static final String AUTHORIZATION_URL = "authorizationUrl";
		public static final String ACCESS_TOKEN_URL = "accessTokenUrl";
		public static final String CONSUMER_KEY = "consumerKey";
		public static final String CONSUMER_SECRET = "consumerSecret";
	}

	public static abstract class Constants {
		public static final String OAUTH_TOKEN_SECRET = "oauth_token_secret";
		public static final String OAUTH_TOKEN = "oauth_token";
		public static final String OAUTH_VERIFIER = "oauth_verifier";
		public static final String OAUTH_PROBLEM = "oauth_problem";
        public static final String OAUTH_ACCESS_DENIED = "access_denied";
	}

    protected void checkError(Request request) throws AuthException{
        final String error = request.getQueryString(Constants.OAUTH_PROBLEM);

        if (error != null) {
            if (error.equals(Constants.OAUTH_ACCESS_DENIED)) {
                throw new AccessDeniedException(getKey());
            } else {
                throw new AuthException(error);
            }
        }
    }

    @Override
	public Object authenticate(final Context context, final Object payload)
			throws AuthException {

		final Request request = context.request();
		final String uri = request.uri();

		if (Logger.isDebugEnabled()) {
			Logger.debug("Returned with URL: '" + uri + "'");
		}

		final Configuration c = getConfiguration();

		final ConsumerKey key = new ConsumerKey(
				c.getString(SettingKeys.CONSUMER_KEY),
				c.getString(SettingKeys.CONSUMER_SECRET));
		final String requestTokenURL = c
				.getString(SettingKeys.REQUEST_TOKEN_URL);
		final String accessTokenURL = c.getString(SettingKeys.ACCESS_TOKEN_URL);
		final String authorizationURL = c
				.getString(SettingKeys.AUTHORIZATION_URL);
		final ServiceInfo info = new ServiceInfo(requestTokenURL,
				accessTokenURL, authorizationURL, key);
		final OAuth service = new OAuth(info, true);

        checkError(request);

        if (uri.contains(Constants.OAUTH_VERIFIER)) {

			final RequestToken rtoken = getStoredToken(context.session(), key); 
					//(RequestToken) PlayAuthenticate.removeFromCache(context.session(), CACHE_TOKEN);
			final String verifier = request.getQueryString(Constants.OAUTH_VERIFIER);
			final Either<OAuthException, RequestToken> retrieveAccessToken = service
					.retrieveAccessToken(rtoken, verifier);

			if (retrieveAccessToken.isLeft()) {
				throw new AuthException(retrieveAccessToken.left().get()
						.getLocalizedMessage());
			} else {
				final I i = buildInfo(retrieveAccessToken.right().get());
				return transform(i);
			}
		} else {

			final String callbackURL = getRedirectUrl(request);

			final Either<OAuthException, RequestToken> response = service
					.retrieveRequestToken(callbackURL);

			if (response.isLeft()) {
				// Exception happened
				throw new AuthException(response.left().get()
						.getLocalizedMessage());
			} else {
				// All good, we have the request token
				final RequestToken rtoken = response.right().get();

				final String token = rtoken.token();
				final String redirectUrl = service.redirectUrl(token);

				//PlayAuthenticate.storeInCache(context.session(), CACHE_TOKEN, rtoken);
				storeToken(context.session(), rtoken);
				return redirectUrl;
			}
		}

	}

	private static void storeToken(final Session session, final RequestToken token) {
		String tokenStr = token.token() + "," + Crypto.encryptAES(token.secret());
		Logger.debug("storing state " + tokenStr);
		PlayAuthenticate.storeTemporarily(session, STATE_TOKEN, tokenStr, STATE_TIMEOUT);
	}
	
	private static RequestToken getStoredToken(final Session session, ConsumerKey key) {
		final String tokenStr = PlayAuthenticate.retrieveStoredTemporarily(session, STATE_TOKEN);
		if(null==tokenStr) return null; 
		
		String[] s = tokenStr.split(",");
		String token = s[0];
		String secret = Crypto.decryptAES(s[1]);
		RequestToken rtoken = new RequestToken(token, secret);

		//Logger.debug("retrieving state " + rtoken);
		return rtoken;

	}
	protected JsonNode signedOauthGet(final String url,
			final OAuthCalculator calculator) {
		final Future<Response> future = WS.url(url).sign(calculator).get();
		final play.api.libs.ws.Response response = play.libs.F.Promise.wrap(future).get(getTimeout());
		final JsValue json = response.json();
		return Json.parse(json.toString());
	}

	protected OAuthCalculator getOAuthCalculator(final OAuth1AuthInfo info) {
		final RequestToken token = new RequestToken(info.getAccessToken(),
				info.getAccessTokenSecret());
		final Configuration c = getConfiguration();
		final ConsumerKey cK = new ConsumerKey(
				c.getString(SettingKeys.CONSUMER_KEY),
				c.getString(SettingKeys.CONSUMER_SECRET));

        return new OAuthCalculator(cK, token);
	}

	/**
	 * This allows custom implementations to enrich an AuthUser object or
	 * provide their own implementation
	 *
	 * @return
	 * @throws AuthException
	 */
	protected abstract U transform(final I identity) throws AuthException;
}
