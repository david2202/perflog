package au.com.securepay.perflog;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.xml.sax.InputSource;

/**
 * Extracts metrics from New Relic over a given time period. By default, this
 * will report on the current day.
 * <p>
 * Parameters
 * <p> 
 * -D <i>n</i> Starts from today - <i>n</i> days. So -D 1 will start
 * from yesterday
 * 
 * @author howed
 * 
 */
public class Run {
	private static final String DAYS_PARAMETER = "-D";
	private static XMLConfiguration apiConfig = new XMLConfiguration();
	private static XMLConfiguration config = new XMLConfiguration();
	private static DateTimeFormatter ISO_FORMAT = ISODateTimeFormat.dateTime();
	private static DateTimeFormatter DISPLAY_TIME_FORMAT = DateTimeFormat
			.forPattern("HH:mm");
	private static DateTimeFormatter DISPLAY_DATE_FORMAT = DateTimeFormat
			.forPattern("dd/MM/yy");
	private static ResponseHandler<String> RESPONSE_HANDLER = new ResponseHandler<String>() {
		public String handleResponse(HttpResponse paramHttpResponse)
				throws ClientProtocolException, IOException {
			int status = paramHttpResponse.getStatusLine().getStatusCode();
			if (status >= 200 && status < 300) {
				HttpEntity entity = paramHttpResponse.getEntity();
				return entity != null ? EntityUtils.toString(entity) : null;
			} else {
				throw new ClientProtocolException(
						"Unexpected response status: " + status);
			}
		}
	};

	// "c:\Program Files\Java\jre7\bin\keytool.exe" -importcert -file
	// NewRelicAPI.cer -keystore my.keystore -storepass password
	public static void main(String[] args) throws Exception {
		// Use a different config file for the API key so we can
		// add it to .gitignore to avoid checking in keys
		apiConfig.setFileName("apiKey.xml");
		apiConfig.setValidating(false);
		apiConfig.load();
		config.setFileName("properties.xml");
		config.setValidating(false);
		config.load();

		Map<String, String> argsMap = parseArgs(args);

		SSLContext sslcontext = SSLContexts
				.custom()
				.loadTrustMaterial(new File("my.keystore"),
						"password".toCharArray(), new TrustSelfSignedStrategy())
				.build();
		// Allow TLSv1 protocol only
		SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
				sslcontext, new String[] { "TLSv1" }, null,
				SSLConnectionSocketFactory.getDefaultHostnameVerifier());
		HttpClientBuilder builder = HttpClients.custom()
				.setSSLSocketFactory(sslsf);
		if (config.containsKey("proxy.host") && config.containsKey("proxy.port")) {
			HttpHost proxy = new HttpHost(config.getString("proxy.host"), config.getInt("proxy.port"), "http");
			builder.setProxy(proxy);
		}
		CloseableHttpClient httpClient = builder.build(); 
				
		Integer interval = config.getInteger("intervalMins", 30);

		try {
			DateTime now = new DateTime();

			DateTime startDate = new DateTime(now.getYear(),
					now.getMonthOfYear(), now.getDayOfMonth(), 0, 0, 0);
			String days = argsMap.get(DAYS_PARAMETER);
			if (days != null) {
				startDate = startDate.minusDays(Integer.valueOf(days));
			}
			printHeadings();

			while (startDate.isBeforeNow()) {
				DateTime endDate = startDate.plusMinutes(interval);

				requestData(httpClient, startDate, endDate);
				startDate = endDate;
			}
		} finally {
			httpClient.close();
		}
	}

	private static void printHeadings() {
		System.out.print("Date");
		System.out.print("\tTime");
		@SuppressWarnings("unchecked")
		List<HierarchicalConfiguration> metrics = config
				.configurationsAt("metrics.metric");
		for (HierarchicalConfiguration metric : metrics) {
			System.out.print("\t");
			System.out.print(metric.getString("name"));
		}
		System.out.println();
	}

	private static void requestData(CloseableHttpClient httpClient,
			DateTime startDate, DateTime endDate) throws Exception {
		System.out.print(startDate.toString(DISPLAY_DATE_FORMAT));
		System.out.print("\t" + startDate.toString(DISPLAY_TIME_FORMAT) + " - "
				+ endDate.toString(DISPLAY_TIME_FORMAT));

		@SuppressWarnings("unchecked")
		List<HierarchicalConfiguration> metrics = config
				.configurationsAt("metrics.metric");

		for (HierarchicalConfiguration metric : metrics) {
			BigDecimal value = new BigDecimal(retrieveMetric(httpClient,
					startDate, endDate, metric.getInt("applicationId"),
					metric.getString("metric"), metric.getString("field"),
					metric.getString("xpath")));
			BigDecimal multiplier = new BigDecimal(metric.getInteger(
					"multiplier", 1));
			value = value.multiply(multiplier).setScale(metric.getInt("scale"),
					RoundingMode.HALF_UP);
			System.out.print("\t" + value);
		}
		System.out.println();
	}

	private static String retrieveMetric(CloseableHttpClient httpclient,
			DateTime startDate, DateTime endDate, Integer applicationId,
			String metric, String field, String xpathExpression)
			throws XPathExpressionException, URISyntaxException,
			ClientProtocolException, IOException {
		URIBuilder builder = new URIBuilder();
		builder.setScheme("https")
				.setHost("api.newrelic.com")
				.setPath(
						"/api/v1/accounts/" + config.getString("account")
								+ "/applications/" + applicationId
								+ "/data.xml")
				.setParameter("metrics[]", metric).setParameter("field", field)
				.setParameter("begin", startDate.toString(ISO_FORMAT))
				.setParameter("end", endDate.toString(ISO_FORMAT))
				.setParameter("summary", "1");
		HttpGet httpGet = new HttpGet(builder.build());

		httpGet.setHeader("x-api-key", apiConfig.getString("apiKey"));

		String responseBody = httpclient.execute(httpGet, RESPONSE_HANDLER);
		InputSource source = new InputSource(new StringReader(responseBody));
		XPathFactory xpathFactory = XPathFactory.newInstance();
		XPath xpath = xpathFactory.newXPath();
		String result = xpath.evaluate(xpathExpression, source);
		return result;
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> argsMap = new HashMap<String, String>();
		int i = 0;
		while (i < args.length) {
			if (args[i].equals(DAYS_PARAMETER)) {
				if (i + 1 == args.length) {
					throw new IllegalArgumentException(
							"-D needs number of days to be specified.");
				}
				try {
					new Integer(args[i + 1]);
				} catch (NumberFormatException nfe) {
					throw new IllegalArgumentException("-D needs a number. "
							+ args[i + 1] + " is not a number.");
				}
				argsMap.put(args[i], args[i + 1]);
				i = i + 2;
			} else {
				throw new IllegalArgumentException(args[i] + " is not valid.");
			}
		}
		return argsMap;
	}
}
