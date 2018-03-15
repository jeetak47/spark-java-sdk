package com.ciscospark;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

import javax.json.Json;
import javax.json.stream.JsonGenerator;

import com.ciscospark.Client.Response;

public class JsonWebReqest<T> extends WebRequestImpl<T>{

	public JsonWebReqest(Client client, String method, java.net.URL uRL, T body) {
		super(client, method, uRL, body);
	}

	@Override
	public Response request() {
		if (client.accessToken == null) {
            if (!authenticate()) {
                throw new NotAuthenticatedException();
            }
        }
        try {
            return doRequest();
        } catch (NotAuthenticatedException ex) {
            if (authenticate()) {
                return doRequest();
            } else {
                throw ex;
            }
        }
	}
	private Response doRequest() {
		
		try {
            HttpURLConnection connection = getConnection(URL);
            String trackingId = connection.getRequestProperty(TRACKING_ID);
            connection.setRequestMethod(method);
            if (client.logger != null && client.logger.isLoggable(Level.FINE)) {
            	client.logger.log(Level.FINE, "Request {0}: {1} {2}",
                        new Object[] { trackingId, method, connection.getURL().toString() });
            }
            if (body != null) {
                connection.setDoOutput(true);
                if (client.logger != null && client.logger.isLoggable(Level.FINEST)) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    writeJson(body, byteArrayOutputStream);
                    client.logger.log(Level.FINEST, "Request Body {0}: {1}",
                            new Object[] { trackingId, byteArrayOutputStream.toString() });
                    byteArrayOutputStream.writeTo(connection.getOutputStream());
                } else {
                    writeJson(body, connection.getOutputStream());
                }
            }

            int responseCode = connection.getResponseCode();
            if (client.logger != null && client.logger.isLoggable(Level.FINE)) {
            	client.logger.log(Level.FINE, "Response {0}: {1} {2}",
                        new Object[] { trackingId, responseCode, connection.getResponseMessage() });
            }
            checkForErrorResponse(connection, responseCode);

            if (client.logger != null && client.logger.isLoggable(Level.FINEST)) {
                InputStream inputStream = logResponse(trackingId, connection.getInputStream());
                return new Response(connection, inputStream);
            } else {
                InputStream inputStream = connection.getInputStream();
                return new Response(connection, inputStream);

            }
        } catch (IOException ex) {
            throw new SparkException("io error", ex);
        }
	}

	
	 private void writeJson(Object body, OutputStream ostream) {
	        JsonGenerator jsonGenerator = Json.createGenerator(ostream);
	        jsonGenerator.writeStartObject();
	        for (Field field : body.getClass().getDeclaredFields()) {
	            field.setAccessible(true);
	            try {
	                Object value = field.get(body);
	                if (value == null) {
	                    continue;
	                }

	                Type type = field.getType();
	                if (type == String.class) {
	                    jsonGenerator.write(field.getName(), (String) value);
	                } else if (type == Integer.class) {
	                    jsonGenerator.write(field.getName(), (Integer) value);
	                } else if (type == BigDecimal.class) {
	                    jsonGenerator.write(field.getName(), (BigDecimal) value);
	                } else if (type == Date.class) {
	                    DateFormat dateFormat = new SimpleDateFormat(ISO8601_FORMAT);
	                    jsonGenerator.write(field.getName(), dateFormat.format(value));
	                } else if (type == URI.class) {
	                    jsonGenerator.write(field.getName(), value.toString());
	                } else if (type == Boolean.class) {
	                    jsonGenerator.write(field.getName(), (Boolean) value);
	                } else if (type == String[].class) {
	                    jsonGenerator.writeStartArray(field.getName());
	                    for (String st : (String[]) value) {
	                        jsonGenerator.write(st);
	                    }
	                    jsonGenerator.writeEnd();
	                } else if (type == URI[].class) {
	                    jsonGenerator.writeStartArray(field.getName());
	                    for (URI uri : (URI[]) value) {
	                        jsonGenerator.write(uri.toString());
	                    }
	                    jsonGenerator.writeEnd();
	                }
	            } catch (IllegalAccessException ex) {
	                // ignore
	            }
	        }
	        jsonGenerator.writeEnd();
	        jsonGenerator.flush();
	        jsonGenerator.close();
	    }
}
