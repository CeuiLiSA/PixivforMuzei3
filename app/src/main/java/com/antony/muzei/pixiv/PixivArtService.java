/*
 *     This file is part of PixivforMuzei3.
 *
 *     PixivforMuzei3 is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program  is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.antony.muzei.pixiv;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.util.Log;

import com.antony.muzei.pixiv.exceptions.AccessTokenAcquisitionException;
import com.antony.muzei.pixiv.gson.OauthResponse;
import com.antony.muzei.pixiv.network.OAuthResponseService;
import com.antony.muzei.pixiv.network.RestClient;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.X509TrustManager;

import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;

public class PixivArtService
{
	private static final String LOG_TAG = "ANTONY_SERVICE";
	private static OkHttpClient httpClient = new OkHttpClient();

	static
	{
		Log.d(LOG_TAG, "locale is : " + Locale.getDefault().getISO3Language());
		/* SNI Bypass begin */
		//if (Locale.getDefault().getISO3Language().equals("zho"))
		{
			Log.d(LOG_TAG, "Bypass in effect");
			HttpLoggingInterceptor httpLoggingInterceptor = new HttpLoggingInterceptor(
					s -> Log.v("ANTONY_SERVICE", "message====" + s));

			httpLoggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);
			OkHttpClient.Builder builder = new OkHttpClient.Builder();

			builder.sslSocketFactory(new RubySSLSocketFactory(), new X509TrustManager()
			{
				@SuppressLint("TrustAllX509TrustManager")
				@Override
				public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
				{

				}

				@SuppressLint("TrustAllX509TrustManager")
				@Override
				public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
				{

				}

				@Override
				public X509Certificate[] getAcceptedIssuers()
				{
					return new X509Certificate[0];
				}
			});//SNI bypass
			builder.hostnameVerifier(new HostnameVerifier()
			{
				@Override
				public boolean verify(String s, SSLSession sslSession)
				{
					return true;
				}
			});//disable hostnameVerifier
			builder.addInterceptor(httpLoggingInterceptor);
			builder.dns(new RubyHttpDns());//define the direct ip address
			httpClient = builder.build();
			/* SNI Bypass end */
		}
	}

	public static String getAccessToken(SharedPreferences sharedPrefs) throws AccessTokenAcquisitionException
	{
		String accessToken = sharedPrefs.getString("accessToken", "");
		long accessTokenIssueTime = sharedPrefs.getLong("accessTokenIssueTime", 0);
		if (!accessToken.isEmpty() && accessTokenIssueTime > (System.currentTimeMillis() / 1000) - 3600)
		{
			Log.i(LOG_TAG, "Existing access token found, using it");
			Log.d(LOG_TAG, "getAccessToken(): Exited");
			return accessToken;
		}
		Log.i(LOG_TAG, "Access token expired or non-existent, proceeding to acquire a new access token");

		try
		{
			Map<String, String> fieldParams = new HashMap<>();
			fieldParams.put("get_secure_url", "1");
			fieldParams.put("client_id", "MOBrBDS8blbauoSck0ZfDbtuzpyT");
			fieldParams.put("client_secret", "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj");
			fieldParams.put("grant_type", "refresh_token");
			fieldParams.put("refresh_token", sharedPrefs.getString("refreshToken", ""));

			OAuthResponseService service = RestClient.getRetrofitOauthInstance().create(OAuthResponseService.class);
			Call<OauthResponse> call = service.postRefreshToken(fieldParams);
			OauthResponse oauthResponse = call.execute().body();
			PixivArtWorker.storeTokens(sharedPrefs, oauthResponse);
			accessToken = oauthResponse.getPixivOauthResponse().getAccess_token();

//			if (authResponseBody.has("has_error"))
//			{
//				throw new AccessTokenAcquisitionException("Error authenticating, check username or password");
//			}

			// Authentication succeeded, storing tokens returned from Pixiv
			//Log.d(LOG_TAG, authResponseBody.toString());
//            Uri profileImageUri = storeProfileImage(authResponseBody.getJSONObject("response"));
//            sharedPrefs.edit().putString("profileImageUri", profileImageUri.toString()).apply();
			//PixivArtWorker.storeTokens(sharedPrefs, authResponseBody.getJSONObject("response"));
		} catch (IOException ex)
		{
			ex.printStackTrace();
			throw new AccessTokenAcquisitionException("getAccessToken(): Exited with error");
		}
		Log.d(LOG_TAG, "Acquired access token");
		Log.d(LOG_TAG, "getAccessToken(): Exited");
		return accessToken;
	}

	// This function is used for modes that require authentication
	// Feed, bookmark, tag_search, or artist
	// Returns a Response containing a JSON within its body
	static Response sendGetRequestAuth(HttpUrl url, String accessToken) throws IOException
	{
		Request request = new Request.Builder()
				.addHeader("User-Agent", PixivArtProviderDefines.APP_USER_AGENT)
				.addHeader("App-OS", PixivArtProviderDefines.APP_OS)
				.addHeader("App-OS-Version", PixivArtProviderDefines.APP_OS_VERSION)
				.addHeader("App-Version", PixivArtProviderDefines.APP_VERSION)
				.addHeader("Authorization", "Bearer " + accessToken)
				.addHeader("Accept-Language", "en-us")
				.get()
				.url(url)
				.build();
		return httpClient.newCall(request).execute();
	}

	// This function used by modes that do not require authentication (ranking) to acquire the JSON
	// Used by all modes to download the actual image
	// Can either return either a:
	//      Response containing a JSON within its body
	//      An image to be downloaded
	// Depending on callee function
	static Response sendGetRequestRanking(HttpUrl url) throws IOException
	{
		Request request = new Request.Builder()
				.addHeader("User-Agent", PixivArtProviderDefines.BROWSER_USER_AGENT)
				.addHeader("Referer", PixivArtProviderDefines.PIXIV_HOST)
				.addHeader("Accept-Language", "en-us")
				.get()
				.url(url)
				.build();

		return httpClient.newCall(request).execute();
	}

	// Returns an access token, provided credentials are correct
	private static Response sendPostRequest(RequestBody authQuery) throws IOException
	{
		// Pixiv API update requires this to prevent replay attacks
		String rfc3339Date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
		String concatSecret = rfc3339Date + HASH_SECRET;
		String hashedSecret = "";
		try
		{
			MessageDigest digest = MessageDigest.getInstance("MD5");
			digest.update(concatSecret.getBytes());
			byte[] messageDigest = digest.digest();
			StringBuilder hexString = new StringBuilder();
			// this loop is horrifically inefficient on CPU and memory
			// but is only executed once to acquire a new access token
			// i.e. at most once per hour for normal use case
			for (byte aMessageDigest : messageDigest)
			{
				StringBuilder h = new StringBuilder(Integer.toHexString(0xFF & aMessageDigest));
				while (h.length() < 2)
				{
					h.insert(0, "0");
				}
				hexString.append(h);
			}
			hashedSecret = hexString.toString();
		} catch (java.security.NoSuchAlgorithmException ex)
		{
			ex.printStackTrace();
		}

		Request request = new Request.Builder()
				.addHeader("Content-Type", "application/x-www-form-urlencoded")
				.addHeader("User-Agent", PixivArtProviderDefines.APP_USER_AGENT)
				.addHeader("x-client-time", rfc3339Date)
				.addHeader("x-client-hash", hashedSecret)
				.post(authQuery)
				.url(PixivArtProviderDefines.OAUTH_URL)
				.build();
		return httpClient.newCall(request).execute();
	}

	static void sendPostRequest(String accessToken, String token)
	{
		HttpUrl rankingUrl = new HttpUrl.Builder()
				.scheme("https")
				.host("app-api.pixiv.net")
				.addPathSegments("v2/illust/bookmark/add")
				.build();
		RequestBody authData = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("illust_id", token)
				.addFormDataPart("restrict", "public")
				.build();
		Request request = new Request.Builder()
				.addHeader("Content-Type", "application/x-www-form-urlencoded")
				.addHeader("User-Agent", PixivArtProviderDefines.APP_USER_AGENT)
				.addHeader("Authorization", "Bearer " + accessToken)
				.post(authData)
				.url(rankingUrl)
				.build();
		try

		{
			httpClient.newCall(request).execute();
		} catch (IOException ex)
		{
			ex.printStackTrace();
		}
	}
}
