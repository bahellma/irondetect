/*
 * #%L
 * =====================================================
 *   _____                _     ____  _   _       _   _
 *  |_   _|_ __ _   _ ___| |_  / __ \| | | | ___ | | | |
 *    | | | '__| | | / __| __|/ / _` | |_| |/ __|| |_| |
 *    | | | |  | |_| \__ \ |_| | (_| |  _  |\__ \|  _  |
 *    |_| |_|   \__,_|___/\__|\ \__,_|_| |_||___/|_| |_|
 *                             \____/
 * 
 * =====================================================
 * 
 * Hochschule Hannover
 * (University of Applied Sciences and Arts, Hannover)
 * Faculty IV, Dept. of Computer Science
 * Ricklinger Stadtweg 118, 30459 Hannover, Germany
 * 
 * Email: trust@f4-i.fh-hannover.de
 * Website: http://trust.f4.hs-hannover.de/
 * 
 * This file is part of irondetect, version 0.0.5, implemented by the Trust@HsH
 * research group at the Hochschule Hannover.
 * %%
 * Copyright (C) 2010 - 2013 Trust@HsH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package de.hshannover.f4.trust.irondetect.ifmap;



import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.TrustManager;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;

import de.hshannover.f4.trust.ifmapj.IfmapJ;
import de.hshannover.f4.trust.ifmapj.binding.IfmapStrings;
import de.hshannover.f4.trust.ifmapj.channel.ARC;
import de.hshannover.f4.trust.ifmapj.channel.SSRC;
import de.hshannover.f4.trust.ifmapj.config.BasicAuthConfig;
import de.hshannover.f4.trust.ifmapj.config.CertAuthConfig;
import de.hshannover.f4.trust.ifmapj.exception.EndSessionException;
import de.hshannover.f4.trust.ifmapj.exception.IfmapErrorResult;
import de.hshannover.f4.trust.ifmapj.exception.IfmapException;
import de.hshannover.f4.trust.ifmapj.exception.InitializationException;
import de.hshannover.f4.trust.ifmapj.identifier.Device;
import de.hshannover.f4.trust.ifmapj.identifier.Identifiers;
import de.hshannover.f4.trust.ifmapj.messages.PollResult;
import de.hshannover.f4.trust.ifmapj.messages.Requests;
import de.hshannover.f4.trust.ifmapj.messages.ResultItem;
import de.hshannover.f4.trust.ifmapj.messages.SearchResult;
import de.hshannover.f4.trust.ifmapj.messages.SubscribeUpdate;
import de.hshannover.f4.trust.irondetect.util.Configuration;
import de.hshannover.f4.trust.irondetect.util.Constants;

/**
 * 
 * @author Ralf Steuerwald
 *
 */
public class DeviceSearcher implements Runnable {

	private static final Logger logger = Logger.getLogger(DeviceSearcher.class);
	
	private IfmapController mController;
	
	/**
	 * The only SSRC
	 */
	private SSRC mSsrc;

	/**
	 * The only ARC
	 */
	private ARC mArc;
	
	public DeviceSearcher(IfmapController controller) {
		mController = controller;
		
		try {
			initSsrc();
			initArc();
		} catch (FileNotFoundException e) {
			logger.error("Could not initialize truststore: " + e.getMessage());
			System.exit(Constants.RETURN_CODE_ERROR_TRUSTSTORE_LOADING_FAILED);
		} catch (InitializationException e) {
			logger.error("Initialization of SSRC and ARC failed: " + e.getMessage() + ", " + e.getCause());
			System.exit(Constants.RETURN_CODE_ERROR_IFMAPJ_INITIALIZATION_FAILED);
		}
	}
	
	/**
	 * Load {@link TrustManager} instances and create {@link SSRC}.
	 * 
	 * @throws FileNotFoundException
	 * @throws InitializationException
	 */
	private void initSsrc() throws FileNotFoundException,
			InitializationException {
		String authMethod = Configuration.ifmapAuthMethod();
		
		String basicUrl = Configuration.ifmapUrlBasic();
		String certUrl = Configuration.ifmapUrlCert();
		String username = Configuration.irondetectPdpSubscriberUser();
		String password = Configuration.irondetectPdpSubscriberPassword();
		String trustStorePath = Configuration.keyStorePath();
		String trustStorePassword = Configuration.keyStorePassword();
		
		try {
			if (authMethod.equalsIgnoreCase("basic")) {
				logger.info("Creating SSRC using basic authentication.");
				BasicAuthConfig basicConfig = new BasicAuthConfig(basicUrl, username, password, trustStorePath, trustStorePassword);
				this.mSsrc = IfmapJ.createSsrc(basicConfig);
			}
			else if (authMethod.equalsIgnoreCase("cert")) {
				logger.info("Creating SSRC using certificate-based authentication.");
				CertAuthConfig certConfig = new CertAuthConfig(certUrl, trustStorePath, trustStorePassword, trustStorePath, trustStorePassword);
				this.mSsrc= IfmapJ.createSsrc(certConfig);
			}
			else {
				throw new IllegalArgumentException("unknown authentication method '" + authMethod + "'");
			}
		} catch (InitializationException e) {
			logger.error("Could not initialize ifmapj: " + e.getMessage() + ", " + e.getCause());
			System.exit(Constants.RETURN_CODE_ERROR_IFMAPJ_INITIALIZATION_FAILED);
		}
	}
	
	/**
	 * @throws InitializationException
	 */
	private void initArc() throws InitializationException {
		mArc = mSsrc.getArc();
	}
	
	@Override
	public void run() {
		initSession();
		
		try {
			subscribeForPdps();
		} catch (IfmapErrorResult e1) {
			logger.error("Got IfmapError: " + e1.getMessage() + ", " + e1.getCause());
		} catch (IfmapException e1) {
			logger.error("Error at polling the MAP server: " + e1.getMessage() + ", " + e1.getCause());
			System.exit(Constants.RETURN_CODE_ERROR_IFMAPJ_EXCEPTION);
		}
		
		while (!Thread.currentThread().isInterrupted()) {
			logger.debug("polling ...");
			
			try {
				PollResult pollResult = mArc.poll();
				
				List<Device> devices = filterForEnpointUpdates(pollResult.getResults()); // TODO filterForEndpointDeletes?
				
				if (devices.size() > 0) {
					for (Device d : devices) {
						logger.debug("submitting new device " + devices + "...");
						mController.submitNewDevice(d);
					}
				}
				
			} catch (IfmapErrorResult e) {
				logger.error("Got IfmapError: " + e.getMessage() + ", " + e.getCause());
			} catch (EndSessionException e) {
				logger.error("The session with the MAP server was closed: " + e.getMessage() + ", " + e.getCause());
				System.exit(Constants.RETURN_CODE_ERROR_IFMAPJ_EXCEPTION);
			} catch (IfmapException e) {
				logger.error("Error at polling the MAP server: " + e.getMessage() + ", " + e.getCause());
				System.exit(Constants.RETURN_CODE_ERROR_IFMAPJ_EXCEPTION);
			}
		}
	}
	
	/**
	 * Filters the list of {@link SearchResult} for {@link Device} identifier.
	 * The resulting list contains only devices updates and search results,
	 * no delete or notify updates.
	 * 
	 * @param results
	 * @return
	 */
	public List<Device> filterForEnpointUpdates(List<SearchResult> results) {
		List<Device> endpoints = new ArrayList<Device>();
		
		for (SearchResult sr : results) {
			
			// process updates/searchs only
			if ( (sr.getType().equals(SearchResult.Type.updateResult)) ||
				 (sr.getType().equals(SearchResult.Type.searchResult)) ) {
				for (ResultItem item : sr.getResultItems()) {
					for (Document doc : item.getMetadata()) {
						
						// search for access-request-device link
						if (doc.getDocumentElement().getLocalName().equals("access-request-device")) {
							
							// extract the device identifier
							if (item.getIdentifier1() instanceof Device) {
								Device dev = (Device) item.getIdentifier1();
								endpoints.add(dev);
							}
							else {
								Device dev = (Device) item.getIdentifier2();
								endpoints.add(dev);
							}
						}
					}
				}
			}
		}
		return endpoints;
	}
	
	private void subscribeForPdps() throws IfmapErrorResult, IfmapException {
		SubscribeUpdate subscribeUpdate = Requests.createSubscribeUpdate();

		// TODO do the following for each PDP

		// set subscription parameters for pdp
		subscribeUpdate.setName(Configuration.subscriberPdp());
		subscribeUpdate.setStartIdentifier(Identifiers.createDev(Configuration
				.subscriberPdp()));
		subscribeUpdate.setMatchLinksFilter(Constants.MATCH_LINKS_PDP);
		subscribeUpdate.setMaxDepth(2);
		subscribeUpdate.setMaxSize(16384);
		subscribeUpdate.setResultFilter(Constants.RESULT_FILTER_PDP);

		// add default namespaces
		subscribeUpdate.addNamespaceDeclaration(IfmapStrings.BASE_PREFIX,
				IfmapStrings.BASE_NS_URI);
		subscribeUpdate.addNamespaceDeclaration(
				IfmapStrings.STD_METADATA_PREFIX,
				IfmapStrings.STD_METADATA_NS_URI);

		logger.debug("Subscribe for new PDP device ...");
		mSsrc.subscribe(Requests.createSubscribeReq(subscribeUpdate));
		logger.debug("Subscription done!");
	}
	
	private void initSession() {
		try {
			mSsrc.newSession(Configuration.ifmapMaxResultSize());
		} catch (IfmapErrorResult e) {
			logger.error("Got IfmapErrorResult: " + e.getMessage() + ", " + e.getCause());
		} catch (IfmapException e) {
			logger.error("Got IfmapException: " + e.getMessage() + ", " + e.getCause());
		}
		
		logger.debug("Session initialized: Session ID: " + mSsrc.getSessionId()
				+ " - Publisher ID : " + mSsrc.getPublisherId() + " (with MAX_POLL_RESULT_SIZE = " + Configuration.ifmapMaxResultSize() + ")");
	}
}
