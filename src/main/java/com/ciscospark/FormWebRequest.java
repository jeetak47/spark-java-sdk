package com.ciscospark;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.UUID;
import java.util.logging.Level;

import com.ciscospark.Client.Response;

public class FormWebRequest<T> extends WebRequestImpl<T> {

	private static final CharSequence LINE_FEED = "\r\n";
	private String boundary="---"+UUID.randomUUID().toString();

	public FormWebRequest(Client client, String method, java.net.URL uRL, T body) {
		super(client, method, uRL, body);
		 
	}

	@Override
	public Response doRequest() {
		try {
			HttpURLConnection connection = this.getConnection(URL);
			 String trackingId = connection.getRequestProperty(TRACKING_ID);
			 if(body!=null){
				 if (client.logger != null && client.logger.isLoggable(Level.FINEST)) {
	                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
	                    writeForm(body, byteArrayOutputStream);
	                    client.logger.log(Level.FINEST, "Request Body {0}: {1}",
	                            new Object[] { trackingId, byteArrayOutputStream.toString() });
	                    byteArrayOutputStream.writeTo(connection.getOutputStream());
	                }else{
	                	writeForm(body, connection.getOutputStream());
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
			
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return null;
	}

	
	private void writeForm(T body, OutputStream outputStream) {
			
		PrintWriter printWriter = new PrintWriter(new OutputStreamWriter(outputStream, Charset.defaultCharset()),true);
		 printWriter.append(LINE_FEED); 
		for (Field field : body.getClass().getDeclaredFields()) {
	            field.setAccessible(true);
	            try {
	                Object value = field.get(body);
	                if (value == null) {
	                    continue;
	                }
				Type type = field.getType();
				System.out.println(field.getName()+ ":"+type.getTypeName()+": "+String.valueOf(value));
				if (type == URI[].class) {
					URI[] uris = (URI[]) value;
					if (uris.length > 0) {
						URI uri = uris[0];
						File file = new File(uri);
						System.out.println(uri+ ": "+file);
						if (file.exists()) {
							writeFile(field.getName(), file, printWriter, outputStream);
						}
					}

				} else {
					writeField(field.getName(), String.valueOf(value), printWriter);
				}
	            } catch (IllegalAccessException ex) {
	                // ignore
	            } catch (IOException e) {
					e.printStackTrace();
				}
	        }
		  
			printWriter.append("--" + boundary + "--").append(LINE_FEED);
			printWriter.close();
		
	}

	@Override
	protected HttpURLConnection getConnection(URL url) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		connection.setUseCaches(false);
		connection.setDoOutput(true); // indicates POST method
		connection.setDoInput(true);
		connection.setRequestProperty("Content-Type",
                "multipart/form-data; boundary=" + boundary);
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

	private void writeField(String name, String value,PrintWriter writer) {
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append("Content-Disposition: form-data; name=\"" + name + "\"")
                .append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.append(value).append(LINE_FEED);
        writer.flush();
    }
	
	public void writeFile(String fieldName, File file,PrintWriter writer,OutputStream outputStream)
            throws IOException {
        String fileName = file.getName();
        writer.append("--" + boundary).append(LINE_FEED);
        writer.append(
                "Content-Disposition: form-data; name=\"" + fieldName
                        + "\"; filename=\"" + fileName + "\"")
                .append(LINE_FEED);
        writer.append(
                "Content-Type: "
                        + URLConnection.guessContentTypeFromName(fileName))
                .append(LINE_FEED);
        writer.append(LINE_FEED);
        writer.flush();
        
        FileInputStream inputStream = new FileInputStream(file);
        byte[] buffer = new byte[4096];
        int bytesRead = -1;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }
        outputStream.flush();
        inputStream.close();
        writer.append(LINE_FEED);
        writer.flush();
    }
	

}
