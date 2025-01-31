package org.openelisglobal.dataexchange.order.controller;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.openelisglobal.common.controller.BaseController;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.services.DisplayListService;
import org.openelisglobal.common.services.DisplayListService.ListType;
import org.openelisglobal.common.services.StatusService.OrderStatus;
import org.openelisglobal.common.util.DateUtil;
import org.openelisglobal.dataexchange.fhir.FhirConfig;
import org.openelisglobal.dataexchange.fhir.FhirUtil;
import org.openelisglobal.dataexchange.order.ElectronicOrderSortOrderCategoryConvertor;
import org.openelisglobal.dataexchange.order.form.ElectronicOrderViewForm;
import org.openelisglobal.dataexchange.order.valueholder.ElectronicOrder;
import org.openelisglobal.dataexchange.order.valueholder.ElectronicOrderDisplayItem;
import org.openelisglobal.dataexchange.service.order.ElectronicOrderService;
import org.openelisglobal.organization.service.OrganizationService;
import org.openelisglobal.organization.util.OrganizationTypeList;
import org.openelisglobal.organization.valueholder.Organization;
import org.openelisglobal.patient.service.PatientService;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.OrderPriority;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.statusofsample.service.StatusOfSampleService;
import org.openelisglobal.test.service.TestService;
import org.openelisglobal.test.valueholder.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

@Controller
public class StudyElectronicOrdersController extends BaseController {

    private static final String[] ALLOWED_FIELDS = new String[] { "searchType", "searchValue", "startDate", "endDate",
            "testIds", "statusId", "useAllInfo", "organizationId", "organizationList" };

    @Autowired
    private StatusOfSampleService statusOfSampleService;
    @Autowired
    private ElectronicOrderService electronicOrderService;
    @Autowired
    private PatientService patientService;
    @Autowired
    private TestService testService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private SampleService sampleService;
    @Autowired
    private FhirUtil fhirUtil;
    @Autowired
    private FhirConfig fhirConfig;

    @InitBinder
    public void initBinder(final WebDataBinder webdataBinder) {
        webdataBinder.registerCustomEditor(ElectronicOrder.SortOrder.class,
                new ElectronicOrderSortOrderCategoryConvertor());
        webdataBinder.setAllowedFields(ALLOWED_FIELDS);
    }

    @RequestMapping(value = "/StudyElectronicOrders", method = RequestMethod.GET)
    public ModelAndView showElectronicOrders(HttpServletRequest request,
            @ModelAttribute("form") @Valid ElectronicOrderViewForm form, BindingResult result) {
        form.setReferralFacilitySelectionList(
                DisplayListService.getInstance().getList(ListType.REFERRAL_ORGANIZATIONS));
        form.setTestSelectionList(DisplayListService.getInstance().getList(ListType.ORDERABLE_TESTS));
        form.setStatusSelectionList(DisplayListService.getInstance().getList(ListType.ELECTRONIC_ORDER_STATUSES));
        form.setOrganizationList(OrganizationTypeList.ARV_ORGS.getList());
        form.setQaEvents(DisplayListService.getInstance().getList(ListType.QA_EVENTS));
        if (form.getSearchType() != null) {
            List<ElectronicOrder> electronicOrders;
            List<ElectronicOrderDisplayItem> eOrderDisplayItems;

            electronicOrders = electronicOrderService.searchForElectronicOrders(form);
            eOrderDisplayItems = convertToDisplayItem(electronicOrders, form.getUseAllInfo());
            form.setSearchFinished(true);
            form.setEOrders(eOrderDisplayItems);
        }

        return findForward(FWD_SUCCESS, form);
    }

    private List<ElectronicOrderDisplayItem> convertToDisplayItem(List<ElectronicOrder> electronicOrders,
            boolean useAllInfo) {
        return electronicOrders.stream().map(e -> convertToDisplayItem(e, useAllInfo)).collect(Collectors.toList());
    }

    private ElectronicOrderDisplayItem convertToDisplayItem(ElectronicOrder electronicOrder, boolean useAllInfo) {
        ElectronicOrderDisplayItem displayItem = new ElectronicOrderDisplayItem();

        try {

            displayItem.setStatus(statusOfSampleService.get(electronicOrder.getStatusId()).getDefaultLocalizedName());
            displayItem.setElectronicOrderId(electronicOrder.getId());
            displayItem.setExternalOrderId(electronicOrder.getExternalId());
            displayItem.setPriority(electronicOrder.getPriority());

            Patient patient = electronicOrder.getPatient();
            if (patient != null) {
                displayItem.setSubjectNumber(patientService.getSubjectNumber(patient));
                displayItem.setPatientNationalId(patient.getNationalId());
                displayItem.setBirthDate(patient.getBirthDateForDisplay());
                displayItem.setGender(patient.getGender());
            } else {
                String errorMsg = "error in data collection - Patient was a null resource";
                displayItem.setWarnings(Arrays.asList(errorMsg));
            }
            Task task = fhirUtil.getFhirParser().parseResource(Task.class, electronicOrder.getData());
            displayItem.setRequestDateDisplay(DateUtil.formatDateAsText(task.getAuthoredOn()));

            Organization organization = organizationService.getOrganizationByFhirId(
                    task.getRestriction().getRecipientFirstRep().getReferenceElement().getIdPart());
            if (organization != null) {
                displayItem.setRequestingFacility(organization.getOrganizationName());
            }

            Sample sample = sampleService.getSampleByReferringId(electronicOrder.getExternalId());
            if (sample != null) {
                displayItem.setLabNumber(sample.getAccessionNumber());
            }
                IGenericClient fhirClient = fhirUtil.getFhirClient(fhirConfig.getLocalFhirStorePath());

                ServiceRequest serviceRequest = fhirClient.read().resource(ServiceRequest.class)
                        .withId(electronicOrder.getExternalId()).execute();
                if (serviceRequest.getRequisition() != null) {
                    displayItem.setReferringLabNumber(serviceRequest.getRequisition().getValue());
                }

                Test test = null;
                for (Coding coding : serviceRequest.getCode().getCoding()) {
                    if (coding.hasSystem()) {
                        if (coding.getSystem().equalsIgnoreCase("http://loinc.org")) {
                            List<Test> tests = testService.getActiveTestsByLoinc(coding.getCode());
                            if (tests.size() != 0) {
                                test = tests.get(0);
                                break;
                            }
                        }
                    }
                }
                if (test != null) {
                    displayItem.setTestName(test.getLocalizedTestName().getLocalizedValue());
                }
        } catch (ResourceNotFoundException e) {
            String errorMsg = "error in data collection - FHIR resource not found";
            displayItem.setWarnings(Arrays.asList(errorMsg));
            LogEvent.logErrorStack(e);
        } catch (NullPointerException e) {
            String errorMsg = "error in data collection - null data";
            displayItem.setWarnings(Arrays.asList(errorMsg));
            LogEvent.logErrorStack(e);
        } catch (RuntimeException e) {
            String errorMsg = "error in data collection - unknown exception";
            displayItem.setWarnings(Arrays.asList(errorMsg));
            LogEvent.logErrorStack(e);
        }

        return displayItem;
    }

    @Override
    protected String findLocalForward(String forward) {
        if (FWD_SUCCESS.equals(forward)) {
            return "studyElectronicOrderViewDefinition";
        } else {
            return "PageNotFound";
        }
    }

    @Override
    protected String getPageTitleKey() {
        return "eorder.browse.title";
    }

    @Override
    protected String getPageSubtitleKey() {
        return "study.eorder.browse.title";
    }
}
