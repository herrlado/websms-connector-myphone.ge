/*
 * Copyright (C) 2010 Felix Bechstein
 * 
 * This file is part of WebSMS.
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; If not, see <http://www.gnu.org/licenses/>.
 */
package org.herrlado.websms.connector.myphone;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.HTTP;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import de.ub0r.android.websms.connector.common.Connector;
import de.ub0r.android.websms.connector.common.ConnectorSpec;
import de.ub0r.android.websms.connector.common.Utils;
import de.ub0r.android.websms.connector.common.WebSMSException;
import de.ub0r.android.websms.connector.common.ConnectorSpec.SubConnectorSpec;

/**
 * Receives commands coming as broadcast from WebSMS.
 * 
 * @author flx
 */
public class ConnectorMyphone extends Connector {
	/** Tag for debug output. */
	private static final String TAG = "WebSMS.myphone";

	/** Login URL, to send Login (POST). */
	private static final String LOGIN_URL = "https://myaccount.myphone.ge";

	/** Send SMS URL(POST) / Free SMS Count URL(GET). */
	private static final String SMS_URL = "https://myaccount.myphone.ge/sms.php";

	/** Encoding to use. */
	private static final String ENCODING = "UTF-8";

	/** HTTP Header User-Agent. */
	private static final String FAKE_USER_AGENT = "Mozilla/5.0 (Windows; U;"
			+ " Windows NT 5.1; de; rv:1.9.0.9) Gecko/2009040821"
			+ " Firefox/3.0.9 (.NET CLR 3.5.30729)";

	/** This String will be matched if the user is logged in. */
	private static final String MATCH_LOGIN_SUCCESS = "act=logout";

	/**
	 * Pattern to extract free sms count from sms page. Looks like.
	 */
	private static final Pattern BALANCE_MATCH_PATTERN = Pattern.compile(
			"<b>(\\d{1,}\\...) GEL</b>", Pattern.DOTALL);

	// public ConnectorMyphone() {
	// Log.w(TAG, "ConnectorMyphone");
	// }

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec initSpec(final Context context) {
		final String name = context.getString(R.string.myphone_name);
		final ConnectorSpec c = new ConnectorSpec(TAG, name);
		c.setAuthor(// .
				context.getString(R.string.myphone_author));
		c.setBalance(null);
		c.setPrefsTitle(context.getString(R.string.preferences));
		c.setCapabilities(ConnectorSpec.CAPABILITIES_UPDATE
				| ConnectorSpec.CAPABILITIES_SEND
				| ConnectorSpec.CAPABILITIES_PREFS);
		c.addSubConnector(TAG, c.getName(),
				SubConnectorSpec.FEATURE_MULTIRECIPIENTS
						| SubConnectorSpec.FEATURE_CUSTOMSENDER
						| SubConnectorSpec.FEATURE_SENDLATER);
		return c;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public final ConnectorSpec updateSpec(final Context context,
			final ConnectorSpec connectorSpec) {
		final SharedPreferences p = PreferenceManager
				.getDefaultSharedPreferences(context);
		if (p.getBoolean(Preferences.ENABLED, false)) {
			if (p.getString(Preferences.PASSWORD, "").length() > 0) {
				connectorSpec.setReady();
			} else {
				connectorSpec.setStatus(ConnectorSpec.STATUS_ENABLED);
			}
		} else {
			connectorSpec.setStatus(ConnectorSpec.STATUS_INACTIVE);
		}
		return connectorSpec;
	}

	// /**
	// * {@inheritDoc}
	// */
	// @Override
	// protected final void doBootstrap(final Context context, final Intent
	// intent)
	// throws WebSMSException {
	// // TODO: bootstrap settings.
	// // If you don't need to bootstrap any config, remove this method.
	// Log.i(TAG, "bootstrap");
	// if (1 != 1) {
	// // If something fails, you should abort this method
	// // by throwing a WebSMSException.
	// throw new WebSMSException("message to user.");
	// }
	// // The surrounding code will assume positive result of this method,
	// // if no WebSMSException was thrown.
	// }

	/**
	 * This post data is needed for log in.
	 * 
	 * @param username
	 *            username
	 * @param password
	 *            password
	 * @return array of params
	 * @throws UnsupportedEncodingException
	 *             if the url can not be encoded
	 */
	private static String getLoginPost(final String username,
			final String password) throws UnsupportedEncodingException {
		final StringBuilder sb = new StringBuilder();
		sb.append("user=");
		sb.append(URLEncoder.encode(username, ENCODING));
		sb.append("&pass=");
		sb.append(URLEncoder.encode(password, ENCODING));
		// sb
		// .append("&login=Login&protocol="
		// + "https&info=Online-Passwort&goto=");
		return sb.toString();
	}

	/**
	 * Login to arcor.
	 * 
	 * @param ctx
	 *            {@link ConnectorContext}
	 * @return true if successfullu logged in, false otherwise.
	 * @throws WebSMSException
	 *             if any Exception occures.
	 */
	private boolean login(final ConnectorContext ctx) throws WebSMSException {
		try {

			final SharedPreferences p = ctx.getPreferences();
			final HttpPost request = createPOST(LOGIN_URL, getLoginPost(p
					.getString(Preferences.USERNAME, ""), p.getString(
					Preferences.PASSWORD, "")));
			final HttpResponse response = ctx.getClient().execute(request);
			final String cutContent = Utils.stream2str(response.getEntity()
					.getContent());
			if (cutContent.indexOf(MATCH_LOGIN_SUCCESS) == -1) {
				throw new WebSMSException(ctx.getContext(), R.string.error_pw);
			}

			notifyFreeCount(ctx, cutContent);

		} catch (final Exception e) {
			throw new WebSMSException(e.getMessage());
		}
		return true;
	}

	/**
	 * Create and Prepare a Post Request. Set also an User-Agent
	 * 
	 * @param url
	 *            http post url
	 * @param urlencodedparams
	 *            key=value pairs as url encoded string
	 * @return HttpPost
	 * @throws Exception
	 *             if an error occures
	 */
	private static HttpPost createPOST(final String url,
			final String urlencodedparams) throws Exception {
		final HttpPost post = new HttpPost(url);
		post.setHeader("User-Agent", FAKE_USER_AGENT);
		post.setHeader(new BasicHeader(HTTP.CONTENT_TYPE,
				URLEncodedUtils.CONTENT_TYPE));
		post.setEntity(new StringEntity(urlencodedparams));
		return post;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doUpdate(final Context context, final Intent intent)
			throws WebSMSException {
		// Log.i(TAG, "update");
		final ConnectorContext ctx = ConnectorContext.create(context, intent);
		this.login(ctx);
		// this.updateBalance(ctx);
		// }
	}

	/**
	 * Updates balance andl pushes it to WebSMS.
	 * 
	 * @param ctx
	 *            {@link ConnectorContext}
	 * @throws WebSMSException
	 *             on an error
	 */
	private void updateBalance(final ConnectorContext ctx)
			throws WebSMSException {
		try {
			final HttpResponse response = ctx.getClient().execute(
					new HttpGet(SMS_URL));
			this.notifyFreeCount(ctx, Utils.stream2str(response.getEntity()
					.getContent()));

		} catch (final Exception ex) {
			throw new WebSMSException(ex.getMessage());
		}
	}

	/**
	 * Push SMS Free Count to WebSMS.
	 * 
	 * @param ctx
	 *            {@link ConnectorContext}
	 * @param content
	 *            conten to investigate.
	 */
	private void notifyFreeCount(final ConnectorContext ctx,
			final String content) {
		final Matcher m = BALANCE_MATCH_PATTERN.matcher(content);
		String term = null;
		if (m.find()) {
			term = m.group(1) + " GEL";
			// } else if (content.contains(MATCH_NO_SMS)) {
			// term = "0+0";
			// } else if (content.contains(MATCH_UNLIMITTED_SMS)) {
			// term = "\u221E";
		} else {
			Log.w(TAG, content);
			term = "?";
		}
		this.getSpec(ctx.getContext()).setBalance(term);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected final void doSend(final Context context, final Intent intent)
			throws WebSMSException {
		// TODO: send a message provided by intent
		// Log.i(TAG, "send with sender "
		// + Utils.getSender(context, new ConnectorCommand(intent)
		// .getDefSender()));
		// See doBootstrap() for more details.
	}
}
