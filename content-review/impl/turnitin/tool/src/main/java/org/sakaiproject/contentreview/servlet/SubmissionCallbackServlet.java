package org.sakaiproject.contentreview.servlet;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONObject;

import org.tsugi.json.IMSJSONRequest;

import org.sakaiproject.authz.api.SecurityAdvisor;
import org.sakaiproject.authz.api.SecurityAdvisor.SecurityAdvice;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.basiclti.util.SakaiBLTIUtil;
import org.sakaiproject.component.cover.ComponentManager;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.contentreview.dao.ContentReviewItem;
import org.sakaiproject.contentreview.service.ContentReviewQueueService;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.lti.api.LTIService;
import org.sakaiproject.turnitin.api.TurnitinLTIAPI;

/** 
 * This servlet will receive callbacks from TII. Then it will process the data
 * related to the submissions and resubmission and store it.
 */

@SuppressWarnings("deprecation")
public class SubmissionCallbackServlet extends HttpServlet {

	private static final Log LOG = LogFactory.getLog(SubmissionCallbackServlet.class);

	private ContentReviewService contentReviewService;
	private ContentReviewQueueService crqServ;
	private LTIService ltiService;
	private TurnitinLTIAPI turnitinLTIAPI;

	@Override
	public void init(ServletConfig config) throws ServletException {
		LOG.debug("init SubmissionCallbackServlet");
		contentReviewService = (ContentReviewService) ComponentManager.get(ContentReviewService.class);
		Objects.requireNonNull(contentReviewService);
		crqServ = (ContentReviewQueueService) ComponentManager.get(ContentReviewQueueService.class);
		Objects.requireNonNull(crqServ);
		ltiService = (LTIService) ComponentManager.get(LTIService.class);
		Objects.requireNonNull(ltiService);
		turnitinLTIAPI = (TurnitinLTIAPI) ComponentManager.get(TurnitinLTIAPI.class);
		Objects.requireNonNull(turnitinLTIAPI);
		super.init(config);
	}

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		LOG.debug("doGet SubmissionCallbackServlet");
		doPost(request, response);
	}

	@SuppressWarnings("unchecked")
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		LOG.debug("doPost SubmissionCallbackServlet");
		String ipAddress = request.getRemoteAddr();
		LOG.debug("Service request from IP=" + ipAddress);

		String allowOutcomes = ServerConfigurationService.getString(SakaiBLTIUtil.BASICLTI_OUTCOMES_ENABLED, SakaiBLTIUtil.BASICLTI_OUTCOMES_ENABLED_DEFAULT);
		if ( ! "true".equals(allowOutcomes) ) {
			allowOutcomes = null;
		}

		if (allowOutcomes == null ) {
			LOG.warn("LTI Services are disabled IP=" + ipAddress);
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			return;
		}

		String contentType = request.getContentType();
		if ( contentType != null && contentType.startsWith("application/json") ) {
			doPostJSON(request, response);
		} else {
			LOG.warn("SubmissionCallbackServlet received a not json call. Callback should have a Content-Type header of application/json.");
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		}
	}

	@SuppressWarnings("unchecked")
	protected void doPostJSON(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		LOG.debug("doPostJSON SubmissionCallbackServlet");

		IMSJSONRequest jsonRequest = new IMSJSONRequest(request);
		if ( jsonRequest.valid ) {
			LOG.debug(jsonRequest.getPostBody());
		}

		String key = turnitinLTIAPI.getGlobalKey();
		String secret = turnitinLTIAPI.getGlobalSecret();

		// Lets check the signature
		if ( key == null || secret == null ) {
			LOG.debug("doPostJSON Deployment is missing credentials");
			response.setStatus(HttpServletResponse.SC_FORBIDDEN); 
			doErrorJSON(request, response, jsonRequest, "Deployment is missing credentials", null);
			return;
		}

		jsonRequest.validateRequest(key, secret, request);
		if ( !jsonRequest.valid ) {
			LOG.debug("doPostJSON OAuth signature failure");
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			doErrorJSON(request, response, jsonRequest, "OAuth signature failure", null);
			return;
		}

		JSONObject json = new JSONObject(jsonRequest.getPostBody());

		String submissionId = json.getString("lis_result_sourcedid");
		// This may look silly, but JSONObject will throw an exception for getString() here
		String tiiPaperId = String.valueOf(json.getInt("paperid"));
		//ext_outcomes_tool_placement_url parameter can also be processed if necessary
		SecurityService securityService = (SecurityService) ComponentManager.get(SecurityService.class);
		SecurityAdvisor yesMan = (String userId, String function, String reference)->{return SecurityAdvice.ALLOWED;};
		try {
			securityService.pushAdvisor(yesMan);
			Optional<ContentReviewItem> cri = crqServ.getQueuedItem(contentReviewService.getProviderId(), submissionId);
			if(!cri.isPresent()) {
				LOG.debug("Could not find the content review item for content " + submissionId);
			} else {
				cri.get().setExternalId(tiiPaperId);
				crqServ.update(cri.get()); // TIITODO: replace this deprecated method with something more appropriate
				LOG.debug("Successfully stored external id into content resource.");
				//NOTE: storing it on the submission too, resubmission process has to be revised
				// TIITODO: fix below. Ideally we don't want to store this in the assignment properties
				/*AssignmentSubmissionEdit ase = AssignmentService.editSubmission(cri.getSubmissionId());
				ResourcePropertiesEdit aPropertiesEdit = ase.getPropertiesEdit();
				aPropertiesEdit.addProperty("turnitin_id", tiiPaperId);
				AssignmentService.commitEditFromCallback(ase);*/
			}
		}
		/*} catch(IdUnusedException | PermissionException | InUseException e) {
			LOG.debug("Could not find submission with id " + submissionId + " or store the TII submission id: " + e.getMessage());
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}*/
		catch (Exception e)
		{
			LOG.error(e);
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
		finally {
			securityService.popAdvisor(yesMan);
		}
	}

	public void doErrorJSON(HttpServletRequest request,HttpServletResponse response, IMSJSONRequest json, String message, Exception e) throws java.io.IOException {
		if (e != null) {
			LOG.error(e.getLocalizedMessage(), e);
		}
		LOG.info(message);
		String output = IMSJSONRequest.doErrorJSON(request, response, json, message, e);
		LOG.warn(output);
	}
}
