package net.strawp.BeebhackDownloader;

/**
 * BeebhackDownloader
 * Class for downloading iPhone video off BBC iPlayer
 * @author Iain Wallace http://strawp.net
 */
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.Random;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class Downloader {

	private static final String CORE_MEDIA_USER_AGENT = "Apple iPhone v1.1.1 CoreMedia v1.0.0.3A110a";
	private static final String IPHONE_MEDIA_SELECTOR_PREFIX = "http://www.bbc.co.uk/mediaselector/3/auth/iplayer_streaming_http_mp4";
	private static final String PLAYLIST_PREFIX = "http://www.bbc.co.uk/iplayer/playlist/";
	private static final long BLOCKSIZE = 524288; // 4194304;

	private boolean debug = true;
	private String downloadDestination;
	private String filename; //TODO this probably shouldn't be a field at all

	private final HttpClient client;
	private final DocumentBuilderFactory documentBuilderFactory;

	public Downloader(final String destination) {
		this.downloadDestination = destination;
		this.client = new DefaultHttpClient();
		this.documentBuilderFactory = DocumentBuilderFactory.newInstance();
		this.client.getParams().setBooleanParameter("http.protocol.handle-redirects", false);
	}

	public void setDownloadDestination(final String downloadDestination) {
		this.downloadDestination = downloadDestination;
	}

	/**
	 * Pass a programme ID and download the file, working out the details automatically
	 * @param pid
	 * @return
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws IOException
	 */
	public boolean downloadByPid( final String pid ) throws IOException, ParserConfigurationException, SAXException{
		assert pid != null;
		String streamUrl = getIphoneStreamUrl(pid);
		if( null == streamUrl ){
			System.out.println("streamUrl is null");
			return false;
		}
		return downloadIphoneStream(streamUrl);
	}

	/**
	 * Work out the stream URL of a programme from the programme ID (pid)
	 * http://download.iplayer.bbc.co.uk/iplayer_streaming_http_mp4/5291651394040737632.mp4?token=iVXcx557SN8malAhHh1%2BZ%2FVk4n35UKfH4qGsJzA1oF%2BhC2K%2Bq9ok8Ky3z%2BkeoBmwfs9pJvH3SUKU%0A8c7z%2BxIG5tNEF2xNIl6%2F%2Bdwl3brQkb6ctOKggoHosrDAVwp5ZPqGNBdQKg%3D%3D%0A
	 * @param pid
	 * @return
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws IOException
	 * @throws IOException
	 * @throws ClientProtocolException
	 */
	private String getIphoneStreamUrl( final String pid ) throws IOException, ParserConfigurationException, SAXException {

		String url = getValidVersionUrl(pid);
		if( null == url ) {
			return null;
		}
		if(this.debug) {
			System.out.println("Getting " + url );
		}

		HttpGet request = new HttpGet(url);
		request.addHeader("User-Agent", CORE_MEDIA_USER_AGENT );
		request.addHeader("Accept", "*/*");
		request.addHeader("Range", "bytes=0-1");

		HttpResponse response = this.client.execute(request);
		Header[] headers = response.getHeaders("Location");

		try {
			return (headers.length == 0) ? null : headers[0].getValue();
		} finally {
			response.getEntity().consumeContent();
		}

	}

	/**
	 * From a pid of a show, get the first valid stream url available
	 * @param pid
	 * @return
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws IOException
	 */
	private String getValidVersionUrl(final String pid) throws IOException, ParserConfigurationException, SAXException{

		for(String i : getVersionPids(pid)) {
			if (null != i) {
				return IPHONE_MEDIA_SELECTOR_PREFIX + "/" + i + "?" + new Random().nextInt(1000000);
			}
		}

		return null;
	}

	/**
	 * Download an iPhone H.264 stream from a URL to a file
	 * @throws IOException
	 * @throws IOException
	 * @throws ClientProtocolException
	 */
	private Boolean downloadIphoneStream( final String url) throws IOException {

		HttpGet request = new HttpGet(url);
		request.addHeader("User-Agent", CORE_MEDIA_USER_AGENT );
		request.addHeader("Accept", "*/*");
		request.addHeader("Range", "bytes=0-1");

		HttpResponse httpResponse = this.client.execute(request);
		Header[] header = httpResponse.getHeaders("Content-Range");
		if( header.length == 0 ) {
			return false;
		}
		String contentRange = header[0].getValue();
		Long downloadLength = Long.parseLong(contentRange.split("/")[1]);
		System.out.println("Download length: " + downloadLength.toString());

		httpResponse.getEntity().consumeContent();

		long filesize = 0;
		File dest = new File( this.downloadDestination + this.filename );
		if( dest.exists() && dest.isFile() ){
			filesize = dest.length();
		}
		long end = 0;

		while( filesize < downloadLength ){
			end = this.downloadBlock(url, this.downloadDestination + this.filename, downloadLength );
			filesize += BLOCKSIZE;
			System.out.println( end + "/" + downloadLength );
		}

		httpResponse.getEntity().consumeContent();
		return true;
	}

	/**
	 * Download a chunk of iPhone video data, return the position in the file of the last byte downloaded
	 * Creates HTTP request using "Range" header
	 * @throws IOException
	 */
	private long downloadBlock( final String url, final String filePath, final Long downloadLength ) throws IOException{
		if(this.debug) {
			System.out.println("downloadBlock( "+ url + ", " + filePath + ", " + downloadLength + " );" );
		}

		File dest = new File(filePath);
		long start = dest.length();
		Long end = Math.min( start + BLOCKSIZE - 1, downloadLength );
		FileOutputStream fos;
		DataOutputStream ds;

		HttpGet request = new HttpGet(url);
		request.addHeader("User-Agent", CORE_MEDIA_USER_AGENT );
		request.addHeader("Accept", "*/*");
		request.addHeader("Range", "bytes=" + start + "-" + end );

		fos = new FileOutputStream(filePath, true);
		// Wrap the FileOutputStream with a
		// DataOutputStream to obtain its writeInt()
		// method.
	    ds = new DataOutputStream( fos );

		HttpResponse response = this.client.execute(request);
		byte[] data = EntityUtils.toByteArray(response.getEntity());
		ds.write(data);

		response.getEntity().consumeContent();
		return end;
	}

	/**
	 * Get the various pids of an episode of a programme, e.g. subtitled, audiodescribed etc
	 * @param pid
	 * @return
	 * @throws SAXException
	 * @throws ParserConfigurationException
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 * @throws MalformedURLException
	 */
	private String[] getVersionPids( final String pid ) throws IOException, ParserConfigurationException, SAXException {
		if( this.debug ) {
			System.out.println("getVersionPids( " + pid + " )");
		}
		Document doc = getDocument(PLAYLIST_PREFIX + pid);
		if(null == doc) {
			return null;
		}

		// Get episode name, use for filename, destination
		NodeList title = doc.getElementsByTagName("title");
		if( title.getLength() > 0 ){
			String strTitle = title.item(0).getNodeValue();
			strTitle = strTitle.replaceAll(":", " -" );
			strTitle = strTitle.replaceAll("/[^-a-zA-Z0-9]/", "");
			this.filename = strTitle.trim() + ".mov";
		}

		NodeList nodes = doc.getElementsByTagName("item");
		int versionCount = nodes.getLength();
		if( versionCount == 0 ) {
			return null;
		}
		String[] aPids = new String[versionCount];
		for(int i=0; i<versionCount; i++){
			Node identifier = nodes.item(i).getAttributes().getNamedItem("identifier");
			if( identifier != null ){
				System.out.println(identifier.getNodeValue());
				aPids[i] = identifier.getNodeValue();
			}
		}
		return aPids;
	}

	private Document getDocument(final String url) throws IOException, ParserConfigurationException, SAXException {
		DocumentBuilder db = this.documentBuilderFactory.newDocumentBuilder();
		Document doc = null;

		HttpGet httpGet = new HttpGet(url);
		HttpResponse httpResponse = this.client.execute(httpGet);
		HttpEntity httpEntity = httpResponse.getEntity();

		if (null != httpEntity) {

			InputStream inputStream = httpEntity.getContent();
			try {
				doc = db.parse(inputStream);
			} finally {
				//close the http connection manually
				inputStream.close();
			}
		}

		return doc;
	}

}
