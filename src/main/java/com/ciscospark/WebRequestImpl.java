package com.ciscospark;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.stream.JsonParser;

import com.ciscospark.Client.Response;

public abstract class WebRequestImpl<T> implements WebRequest {
	protected static final String TRACKING_ID = "TrackingID";
	public static final String ISO8601_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
	private static final Pattern linkPattern = Pattern.compile("\\s*<(\\S+)>\\s*;\\s*rel=\"(\\S+)\",?");

	Client client;
	String method;
	URL URL;
	T body;

	public WebRequestImpl(Client client, String method, URL uRL, T body) {
		super();
		this.client = client;
		this.method = method;
		URL = uRL;
		this.body = body;
	}

	protected boolean authenticate() {
		if (client.clientId != null && client.clientSecret != null) {
			if (client.authCode != null && client.redirectUri != null) {
				// log(Level.FINE, "Requesting access token");
				URL url = client.getUrl("/access_token", null);
				AccessTokenRequest body = new AccessTokenRequest();
				body.setGrant_type("authorization_code");
				body.setClient_id(client.clientId);
				body.setClient_secret(client.clientSecret);
				body.setCode(client.authCode);
				body.setRedirect_uri(client.redirectUri);
				Response response = new JsonWebReqest<AccessTokenRequest>(client,"POST", url, body).request();
				AccessTokenResponse responseBody = readJson(AccessTokenResponse.class, response.inputStream);
				client.accessToken = responseBody.getAccess_token();
				client.refreshToken = responseBody.getRefresh_token();
				client.authCode = null;
				return true;
			} else if (client.refreshToken != null) {
				// log(Level.FINE, "Refreshing access token");
				URL url = client.getUrl("/access_token", null);
				AccessTokenRequest body = new AccessTokenRequest();
				body.setClient_id(client.clientId);
				body.setClient_secret(client.clientSecret);
				body.setRefresh_token(client.refreshToken);
				body.setGrant_type("refresh_token");
				Response response = new JsonWebReqest<AccessTokenRequest>(client,"POST", url, body).request();
				AccessTokenResponse responseBody = readJson(AccessTokenResponse.class, response.inputStream);
				client.accessToken = responseBody.getAccess_token();
				return true;
			}
		}
		return false;
	}

	protected static <T> T readJson(Class<T> clazz, InputStream inputStream) {
		JsonParser parser = Json.createParser(inputStream);
		parser.next();
		return readObject(clazz, parser);
	}

	protected static <T> T readObject(Class<T> clazz, JsonParser parser) {
		try {
			T result = clazz.newInstance();
			List<Object> list = null;
			Field field = null;
			PARSER_LOOP: while (parser.hasNext()) {
				JsonParser.Event event = parser.next();
				switch (event) {
				case KEY_NAME:
					String key = parser.getString();
					try {
						field = clazz.getDeclaredField(key);
						field.setAccessible(true);
					} catch (Exception ex) {
						// ignore
					}
					break;
				case VALUE_FALSE:
					if (field != null) {
						field.set(result, false);
						field = null;
					}
					break;
				case VALUE_TRUE:
					if (field != null) {
						field.set(result, true);
						field = null;
					}
					break;
				case VALUE_NUMBER:
					if (field != null) {
						Object value = (parser.isIntegralNumber() ? parser.getInt() : parser.getBigDecimal());
						field.set(result, value);
						field = null;
					}
					break;
				case VALUE_STRING:
					if (list != null) {
						list.add(parser.getString());
					} else if (field != null) {
						if (field.getType().isAssignableFrom(String.class)) {
							field.set(result, parser.getString());
						} else if (field.getType().isAssignableFrom(Date.class)) {
							DateFormat dateFormat = new SimpleDateFormat(ISO8601_FORMAT);
							field.set(result, dateFormat.parse(parser.getString()));
						} else if (field.getType().isAssignableFrom(URI.class)) {
							field.set(result, URI.create(parser.getString()));
						}
						field = null;
					}
					break;
				case VALUE_NULL:
					field = null;
					break;
				case START_ARRAY:
					list = new ArrayList<Object>();
					break;
				case END_ARRAY:
					if (field != null) {
						Class itemClazz;
						if (field.getType().equals(String[].class)) {
							itemClazz = String.class;
						} else if (field.getType().equals(URI[].class)) {
							itemClazz = URI.class;
							ListIterator<Object> iterator = list.listIterator();
							while (iterator.hasNext()) {
								Object next = iterator.next();
								iterator.set(URI.create(next.toString()));
							}
						} else {
							throw new SparkException("bad field class: " + field.getType());
						}
						Object array = Array.newInstance(itemClazz, list.size());
						field.set(result, list.toArray((Object[]) array));
						field = null;
					}
					list = null;
					break;
				case END_OBJECT:
					break PARSER_LOOP;
				default:
					throw new SparkException("bad json event: " + event);
				}
			}
			return result;
		} catch (SparkException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new SparkException(ex);
		}
	}

	public HttpURLConnection getLink(HttpURLConnection connection, String rel) throws IOException {
		String link = connection.getHeaderField("Link");
		return parseLinkHeader(link, rel);
	}

	public HttpURLConnection parseLinkHeader(String link, String desiredRel) throws IOException {
		HttpURLConnection result = null;
		if (link != null && !"".equals(link)) {
			Matcher matcher = linkPattern.matcher(link);
			while (matcher.find()) {
				String url = matcher.group(1);
				String foundRel = matcher.group(2);
				if (desiredRel.equals(foundRel)) {
					result = getConnection(new URL(url));
					break;
				}
			}
		}
		return result;
	}
	
	 protected InputStream logResponse(String trackingId, InputStream inputStream) throws IOException {
	        if (inputStream == null) {
	            return null;
	        }
	        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
	        byte buf[] = new byte[16 * 1024];
	        int count;
	        while ((count = inputStream.read(buf)) != -1) {
	            byteArrayOutputStream.write(buf, 0, count);
	        }
	        client.logger.log(Level.FINEST, "Response Body {0}: {1}",
	                new Object[]{trackingId, byteArrayOutputStream.toString()});
	        return new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
	    }
	 
	 protected void checkForErrorResponse(HttpURLConnection connection, int responseCode) throws NotAuthenticatedException, IOException {
	        if (responseCode == 401) {
	            throw new NotAuthenticatedException();
	        } else if (responseCode < 200 || responseCode >= 400) {
	            final StringBuilder errorMessageBuilder = new StringBuilder("bad response code ");
	            errorMessageBuilder.append(responseCode);
	            try {
	                String responseMessage = connection.getResponseMessage();
	                if (responseMessage != null) {
	                    errorMessageBuilder.append(" ");
	                    errorMessageBuilder.append(responseMessage);
	                }
	            } catch (IOException ex) {
	                // ignore
	            }


	            ErrorMessage errorMessage;
	            if (client.logger != null && client.logger.isLoggable(Level.FINEST)) {
	                InputStream inputStream = logResponse(connection.getRequestProperty(TRACKING_ID), connection.getErrorStream());
	                errorMessage = parseErrorMessage(inputStream);
	            } else {
	                errorMessage = parseErrorMessage(connection.getErrorStream());
	            }

	            if (errorMessage != null) {
	                errorMessageBuilder.append(": ");
	                errorMessageBuilder.append(errorMessage.message);
	            }

	            throw new SparkException(errorMessageBuilder.toString());
	        }
	    }
	 
	 static class ErrorMessage {
	        String message;
	        String trackingId;
	    }

	 protected static ErrorMessage parseErrorMessage(InputStream errorStream) {
	        try {
	            if (errorStream == null) {
	                return null;
	            }
	            JsonReader reader = Json.createReader(errorStream);
	            JsonObject jsonObject = reader.readObject();
	            ErrorMessage result = new ErrorMessage();
	            result.message = jsonObject.getString("message");
	            result.trackingId = jsonObject.getString("trackingId");
	            return result;
	        } catch (Exception ex) {
	            return null;
	        }
	    }
	    
	    protected HttpURLConnection getConnection(URL url) throws IOException {
	    	HttpURLConnection connection;
	    	if(client.proxyHost!=null){
	    		SocketAddress addr = new InetSocketAddress(client.proxyHost, client.proxyPort==0?80:client.proxyPort);
	    		Proxy proxy = new Proxy(Proxy.Type.HTTP,addr);
	    		 connection = (HttpURLConnection) url.openConnection(proxy);	
	    	}else {
	    		 connection = (HttpURLConnection) url.openConnection();
			}
	    	connection.setConnectTimeout(0);
	    	connection.setReadTimeout(0);
	    	connection.setRequestProperty("Content-type", "application/json");
	        if (client.accessToken != null) {
	            String authorization = client.accessToken;
	            if (!authorization.startsWith("Bearer ")) {
	                authorization = "Bearer " + client.accessToken;
	            }
	            connection.setRequestProperty("Authorization", authorization);
	        }
	        connection.setRequestProperty(TRACKING_ID, UUID.randomUUID().toString());
	        return connection;
	    }
}
