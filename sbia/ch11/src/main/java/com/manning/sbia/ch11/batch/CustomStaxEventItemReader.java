/**
 * 
 */
package com.manning.sbia.ch11.batch;

import java.io.InputStream;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.batch.item.file.ResourceAwareItemReaderItemStream;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.batch.item.xml.StaxEventItemReader;
import org.springframework.batch.item.xml.stax.DefaultFragmentEventReader;
import org.springframework.batch.item.xml.stax.FragmentEventReader;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.oxm.Unmarshaller;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.xml.StaxUtils;

/**
 * Nasty workaround to solve http://jira.springframework.org/browse/BATCH-1532.
 * We need to wait for Spring Batch 3.0 to remove this.
 * @author acogoluegnes
 *
 */
public class CustomStaxEventItemReader<T> extends AbstractItemCountingItemStreamItemReader<T> implements
		ResourceAwareItemReaderItemStream<T>, InitializingBean {

	private static final Log logger = LogFactory.getLog(StaxEventItemReader.class);

	private FragmentEventReader fragmentReader;

	private XMLEventReader eventReader;

	private Unmarshaller unmarshaller;

	private Resource resource;

	private InputStream inputStream;

	private String fragmentRootElementName;

	private boolean noInput;

	private boolean strict = true;

	private String fragmentRootElementNameSpace;

	public CustomStaxEventItemReader() {
		setName(ClassUtils.getShortName(StaxEventItemReader.class));
	}

	/**
	 * In strict mode the reader will throw an exception on
	 * {@link #open(org.springframework.batch.item.ExecutionContext)} if the
	 * input resource does not exist.
	 * @param strict false by default
	 */
	public void setStrict(boolean strict) {
		this.strict = strict;
	}

	public void setResource(Resource resource) {
		this.resource = resource;
	}

	/**
	 * @param unmarshaller maps xml fragments corresponding to records to
	 * objects
	 */
	public void setUnmarshaller(Unmarshaller unmarshaller) {
		this.unmarshaller = unmarshaller;
	}

	/**
	 * @param fragmentRootElementName name of the root element of the fragment
	 */
	public void setFragmentRootElementName(String fragmentRootElementName) {
		this.fragmentRootElementName = fragmentRootElementName;
	}

	/**
	 * Ensure that all required dependencies for the ItemReader to run are
	 * provided after all properties have been set.
	 * 
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
	 * @throws IllegalArgumentException if the Resource, FragmentDeserializer or
	 * FragmentRootElementName is null, or if the root element is empty.
	 * @throws IllegalStateException if the Resource does not exist.
	 */
	public void afterPropertiesSet() throws Exception {
		Assert.notNull(unmarshaller, "The Unmarshaller must not be null.");
		Assert.hasLength(fragmentRootElementName, "The FragmentRootElementName must not be null");
		if (fragmentRootElementName.contains("{")) {
			fragmentRootElementNameSpace = fragmentRootElementName.replaceAll("\\{(.*)\\}.*", "$1");
			fragmentRootElementName = fragmentRootElementName.replaceAll("\\{.*\\}(.*)", "$1");
		}
	}

	/**
	 * Responsible for moving the cursor before the StartElement of the fragment
	 * root.
	 * 
	 * This implementation simply looks for the next corresponding element, it
	 * does not care about element nesting. You will need to override this
	 * method to correctly handle composite fragments.
	 * 
	 * @return <code>true</code> if next fragment was found, <code>false</code>
	 * otherwise.
	 */
	protected boolean moveCursorToNextFragment(XMLEventReader reader) {
		try {
			while (true) {
				while (reader.peek() != null && !reader.peek().isStartElement()) {
					reader.nextEvent();
				}
				if (reader.peek() == null) {
					return false;
				}
				QName startElementName = ((StartElement) reader.peek()).getName();
				if (startElementName.getLocalPart().equals(fragmentRootElementName)) {
					if (fragmentRootElementNameSpace==null || startElementName.getNamespaceURI().equals(fragmentRootElementNameSpace)) {
						return true;
					}
				}
				reader.nextEvent();

			}
		}
		catch (XMLStreamException e) {
			throw new DataAccessResourceFailureException("Error while reading from event reader", e);
		}
	}

	protected void doClose() throws Exception {
		try {
			if (fragmentReader != null) {
				fragmentReader.close();
			}
			if (inputStream != null) {
				inputStream.close();
			}
		}
		finally {
			fragmentReader = null;
			inputStream = null;
		}

	}

	protected void doOpen() throws Exception {
		Assert.notNull(resource, "The Resource must not be null.");

		noInput = false;
		if (!resource.exists()) {
			if (strict) {
				throw new IllegalStateException("Input resource must exist (reader is in 'strict' mode)");
			}
			noInput = true;
			logger.warn("Input resource does not exist " + resource.getDescription());
			return;
		}
		if (!resource.isReadable()) {
			if (strict) {
				throw new IllegalStateException("Input resource must be readable (reader is in 'strict' mode)");
			}
			noInput = true;
			logger.warn("Input resource is not readable " + resource.getDescription());
			return;
		}

		inputStream = resource.getInputStream();
		eventReader = XMLInputFactory.newInstance().createXMLEventReader(inputStream);
		fragmentReader = new DefaultFragmentEventReader(eventReader);

	}

	/**
	 * Move to next fragment and map it to item.
	 */
	protected T doRead() throws Exception {

		if (noInput) {
			return null;
		}

		T item = null;

		if (moveCursorToNextFragment(fragmentReader)) {
			fragmentReader.markStartFragment();

			@SuppressWarnings("unchecked")
			T mappedFragment = (T) unmarshaller.unmarshal(StaxUtils.createStaxSource(fragmentReader));

			item = mappedFragment;
			fragmentReader.markFragmentProcessed();
		}

		return item;
	}

	/*
	 * jumpToItem is overridden because reading in and attempting to bind an
	 * entire fragment is unacceptable in a restart scenario, and may cause
	 * exceptions to be thrown that were already skipped in previous runs.
	 */
	@Override
	protected void jumpToItem(int itemIndex) throws Exception {
		for (int i = 0; i < itemIndex; i++) {
			readToStartFragment();
			readToEndFragment();
		}
	}

	/*
	 * Read until the first StartElement tag that matches the provided
	 * fragmentRootElementName. Because there may be any number of tags in
	 * between where the reader is now and the fragment start, this is done in a
	 * loop until the element type and name match.
	 */
	private void readToStartFragment() throws XMLStreamException {
		while (true) {
			XMLEvent nextEvent = eventReader.nextEvent();
			if (nextEvent.isStartElement()
					&& ((StartElement) nextEvent).getName().getLocalPart().equals(fragmentRootElementName)) {
				return;
			}
		}
	}

	/*
	 * Read until the first EndElement tag that matches the provided
	 * fragmentRootElementName. Because there may be any number of tags in
	 * between where the reader is now and the fragment end tag, this is done in
	 * a loop until the element type and name match
	 */
	private void readToEndFragment() throws XMLStreamException {
		while (true) {
			XMLEvent nextEvent = eventReader.nextEvent();
			if (nextEvent.isEndElement()
					&& ((EndElement) nextEvent).getName().getLocalPart().equals(fragmentRootElementName)) {
				return;
			}
		}
	}
	
	
}
