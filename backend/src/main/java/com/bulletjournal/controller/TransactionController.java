package com.bulletjournal.controller;

import com.bulletjournal.clients.UserClient;
import com.bulletjournal.contents.ContentAction;
import com.bulletjournal.contents.ContentType;
import com.bulletjournal.controller.models.*;
import com.bulletjournal.controller.models.params.*;
import com.bulletjournal.controller.utils.EtagGenerator;
import com.bulletjournal.controller.utils.ZonedDateTimeHelper;
import com.bulletjournal.es.ESUtil;
import com.bulletjournal.exceptions.BadRequestException;
import com.bulletjournal.ledger.FrequencyType;
import com.bulletjournal.ledger.LedgerSummary;
import com.bulletjournal.ledger.LedgerSummaryCalculator;
import com.bulletjournal.ledger.LedgerSummaryType;
import com.bulletjournal.messaging.FreeMarkerClient;
import com.bulletjournal.messaging.MessagingService;
import com.bulletjournal.messaging.OpenHtmlConverter;
import com.bulletjournal.notifications.Auditable;
import com.bulletjournal.notifications.Event;
import com.bulletjournal.notifications.NotificationService;
import com.bulletjournal.notifications.RemoveElasticsearchDocumentEvent;
import com.bulletjournal.notifications.informed.Informed;
import com.bulletjournal.notifications.informed.RemoveTransactionEvent;
import com.bulletjournal.notifications.informed.UpdateTransactionPayerEvent;
import com.bulletjournal.repository.GroupDaoJpa;
import com.bulletjournal.repository.ProjectDaoJpa;
import com.bulletjournal.repository.TransactionDaoJpa;
import com.bulletjournal.repository.TransactionRepository;
import com.bulletjournal.repository.models.ContentModel;
import com.bulletjournal.repository.models.ProjectItemModel;
import com.bulletjournal.repository.models.TransactionContent;
import freemarker.template.TemplateException;
import java.io.IOException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.HttpHeaders.IF_NONE_MATCH;

@RestController
public class TransactionController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionController.class);

    protected static final String TRANSACTIONS_ROUTE = "/api/projects/{projectId}/transactions";
    protected static final String RECURRING_TRANSACTIONS_ROUTE = "/api/projects/{projectId}/recurringTransactions";
    protected static final String TRANSACTION_ROUTE = "/api/transactions/{transactionId}";
    protected static final String TRANSACTION_SET_LABELS_ROUTE = "/api/transactions/{transactionId}/setLabels";
    protected static final String TRANSACTION_SET_COLOR_ROUTE = "/api/transactions/{transactionId}/setColor";
    protected static final String TRANSACTION_SET_BANK_ACCOUNT_ROUTE = "/api/transactions/{transactionId}/setBankAccount";
    protected static final String MOVE_TRANSACTION_ROUTE = "/api/transactions/{transactionId}/move";
    protected static final String SHARE_TRANSACTION_ROUTE = "/api/transactions/{transactionId}/share";
    protected static final String ADD_CONTENT_ROUTE = "/api/transactions/{transactionId}/addContent";
    protected static final String CONTENT_ROUTE = "/api/transactions/{transactionId}/contents/{contentId}";
    protected static final String CONTENTS_ROUTE = "/api/transactions/{transactionId}/contents";
    protected static final String CONTENT_REVISIONS_ROUTE = "/api/transactions/{transactionId}/contents/{contentId}/revisions/{revisionId}";
    protected static final String SET_CONTENTS_ORDER_ROUTE = "/api/transactions/{transactionId}/contents/setOrder";
    protected static final String TRANSACTION_EXPORT_EMAIL_ROUTE = "/api/transactions/{transactionId}/exportEmail";
    protected static final String TRANSACTION_EXPORT_PDF_ROUTE = "/api/transactions/{transactionId}/exportPdf";
    protected static final String TRANSACTION_EXPORT_IMAGE_ROUTE = "/api/transactions/{transactionId}/exportImage";


    @Autowired
    private LedgerSummaryCalculator ledgerSummaryCalculator;

    @Autowired
    private TransactionDaoJpa transactionDaoJpa;

    @Autowired
    private ProjectDaoJpa projectDaoJpa;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserClient userClient;

    @Autowired
    private FreeMarkerClient freeMarkerClient;

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private GroupDaoJpa groupDaoJpa;

    @GetMapping(RECURRING_TRANSACTIONS_ROUTE)
    public List<Transaction> getRecurringTransactions(@NotNull @PathVariable Long projectId) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        List<Transaction> transactions = this.transactionDaoJpa.getRecurringTransactions(username, projectId);
        transactions = ProjectItem.addAvatar(transactions, this.userClient);
        return transactions;
    }

    @GetMapping(TRANSACTIONS_ROUTE)
    public ResponseEntity<?> getTransactions(@NotNull @PathVariable Long projectId,
                                             @NotNull @RequestParam(required = false) FrequencyType frequencyType,
                                             @NotBlank @RequestParam String timezone,
                                             @NotNull @RequestParam LedgerSummaryType ledgerSummaryType,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate,
                                             @RequestParam(required = false) String payer,
                                             @RequestParam(required = false) List<Long> labelsToKeep,
                                             @RequestParam(required = false) List<Long> labelsToRemove) {

        Pair<ZonedDateTime, ZonedDateTime> startEndTime = getStartEndTime(frequencyType, timezone, startDate,
                endDate);
        ZonedDateTime startTime = startEndTime.getLeft();
        ZonedDateTime endTime = startEndTime.getRight();

        if (StringUtils.isNotBlank(payer)) {
            return getTransactionsByPayer(projectId, payer, startTime, endTime);
        }

        String username = MDC.get(UserClient.USER_NAME_KEY);
        List<Transaction> transactions = ProjectItem.addAvatar(
                this.transactionDaoJpa.getTransactions(projectId, startTime, endTime, username),
                this.userClient);

        transactions = transactions.stream().filter(
                t -> includeProjectItem(labelsToKeep, labelsToRemove, t)).collect(Collectors.toList());

        String transactionsEtag = EtagGenerator.generateEtag(EtagGenerator.HashAlgorithm.MD5,
                EtagGenerator.HashType.TO_HASHCODE, transactions);

        HttpHeaders responseHeader = new HttpHeaders();
        responseHeader.setETag(transactionsEtag);

        final LedgerSummary ledgerSummary = this.ledgerSummaryCalculator.getLedgerSummary(ledgerSummaryType,
                startTime, endTime, transactions, frequencyType);

        return ResponseEntity.ok().headers(responseHeader).body(ledgerSummary);
    }

    private boolean includeProjectItem(
            List<Long> labelsToKeep, List<Long> labelsToRemove, Transaction transaction) {
        List<Long> labels = transaction.getLabels().stream().map(l -> l.getId()).collect(Collectors.toList());
        final List<Long> keep = labelsToKeep == null ? Collections.emptyList() : labelsToKeep;
        final List<Long> remove = labelsToRemove == null ? Collections.emptyList() : labelsToRemove;
        if (labels.stream().anyMatch(l -> remove.contains(l))) {
            return false;
        }

        if (keep.isEmpty()) {
            return true;
        }

        // labelsToKeep.length > 0
        if (!labels.stream().anyMatch(l -> keep.contains(l))) {
            return false;
        }
        return true;
    }

    private ResponseEntity<List<Transaction>> getTransactionsByPayer(Long projectId, String payer,
                                                                     ZonedDateTime startTime, ZonedDateTime endTime) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        List<Transaction> transactions = this.transactionDaoJpa.getTransactionsByPayer(projectId, username,
                payer, startTime, endTime);
        return ResponseEntity.ok().body(ProjectItem.addAvatar(transactions, this.userClient));
    }

    @PostMapping(TRANSACTIONS_ROUTE)
    @ResponseStatus(HttpStatus.CREATED)
    public Transaction createTransaction(@NotNull @PathVariable Long projectId,
                                         @Valid @RequestBody CreateTransactionParams createTransactionParams) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        com.bulletjournal.repository.models.Transaction createdTransaction = this.transactionDaoJpa.create(projectId,
                username, createTransactionParams);
        String projectName = createdTransaction.getProject().getName();

        this.notificationService.trackActivity(new Auditable(projectId,
                "created Transaction ##" + createdTransaction.getName() + "## in BuJo ##" + projectName
                        + "##",
                username, createdTransaction.getId(), Timestamp.from(Instant.now()),
                ContentAction.ADD_TRANSACTION));

        return createdTransaction.toPresentationModel();
    }

    @GetMapping(TRANSACTION_ROUTE)
    public Transaction getTransaction(@NotNull @PathVariable Long transactionId) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        Transaction transaction = this.transactionDaoJpa.getTransaction(username, transactionId);
        return ProjectItem.addAvatar(transaction, this.userClient);
    }

    @PatchMapping(TRANSACTION_ROUTE)
    public Transaction updateTransaction(@NotNull @PathVariable Long transactionId,
                                         @Valid @RequestBody UpdateTransactionParams updateTransactionParams) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        Pair<List<Event>, com.bulletjournal.repository.models.Transaction> res = transactionDaoJpa
                .partialUpdate(username, transactionId, updateTransactionParams);
        List<Event> events = res.getLeft();
        String projectName = res.getRight().getName();

        if (!events.isEmpty()) {
            notificationService.inform(new UpdateTransactionPayerEvent(events, username,
                    updateTransactionParams.getPayer()));
        }
        Transaction transaction = getTransaction(transactionId);
        this.notificationService
                .trackActivity(new Auditable(transaction.getProjectId(),
                        "updated Transaction ##" + transaction.getName() + "## in BuJo ##"
                                + projectName + "##",
                        username, transactionId, Timestamp.from(Instant.now()),
                        ContentAction.UPDATE_TRANSACTION));
        return transaction;
    }

    @DeleteMapping(TRANSACTION_ROUTE)
    public void deleteTransaction(@NotNull @PathVariable Long transactionId,
                                  @RequestParam(required = false) Optional<String> dateTime) {
        String username = MDC.get(UserClient.USER_NAME_KEY);

        List<String> deleteESDocumentIds = this.transactionDaoJpa.getDeleteESDocumentIdsForProjectItem(username, transactionId);

        Pair<List<Event>, com.bulletjournal.repository.models.Transaction> res = this.transactionDaoJpa
                .delete(username, transactionId, dateTime.orElse(null));
        List<Event> events = res.getLeft();
        if (!events.isEmpty()) {
            Long projectId = res.getRight().getProject().getId();
            String transactionName = res.getRight().getName();
            this.notificationService.inform(new RemoveTransactionEvent(events, username));
            this.notificationService.deleteESDocument(new RemoveElasticsearchDocumentEvent(deleteESDocumentIds));
            this.notificationService.trackActivity(new Auditable(projectId,
                    "deleted Transaction ##" + transactionName + "##", username, transactionId,
                    Timestamp.from(Instant.now()), ContentAction.DELETE_TRANSACTION));
        }
    }

    @DeleteMapping(TRANSACTIONS_ROUTE)
    public void deleteTransactions(@NotNull @PathVariable Long projectId,
                                   @NotNull @RequestParam List<String> transactions) {
        // curl -X DELETE
        // "http://localhost:8080/api/projects/11/transactions?transactions=12&transactions=11&transactions=13&transactions=14"
        // -H "accept: */*"
        if (transactions.isEmpty()) {
            return;
        }

        String username = MDC.get(UserClient.USER_NAME_KEY);
        com.bulletjournal.repository.models.Project project = this.projectDaoJpa.getProject(projectId, username);
        List<Long> transactionIds = new ArrayList<>();
        for (String transaction : transactions) {
            int separator = transaction.indexOf('#');
            if (separator < 0) {
                transactionIds.add(Long.parseLong(transaction));
                continue;
            }

            this.transactionDaoJpa.delete(username,
                    Long.parseLong(transaction.substring(0, separator)),
                    transaction.substring(separator + 1));
        }

        if (transactionIds.isEmpty()) {
            return;
        }
        List<com.bulletjournal.repository.models.Transaction> transactionList =
                this.transactionDaoJpa.findAllById(transactionIds, project).stream()
                        .filter(Objects::nonNull)
                        .map(t -> (com.bulletjournal.repository.models.Transaction) t)
                        .collect(Collectors.toList());
        if (transactionList.isEmpty()) {
            return;
        }

        this.transactionRepository.deleteInBatch(transactionList);

        List<String> deleteESDocumentIds = ESUtil.getProjectItemSearchIndexIds(transactionIds, ContentType.TRANSACTION);
        this.notificationService.deleteESDocument(new RemoveElasticsearchDocumentEvent(deleteESDocumentIds));

        for (com.bulletjournal.repository.models.Transaction transaction : transactionList) {
            this.notificationService.trackActivity(
                    new Auditable(projectId, "deleted Transaction ##" + transaction.getName() +
                            "## in BuJo ##" + project.getName() + "##", username,
                            transaction.getId(), Timestamp.from(Instant.now()), ContentAction.DELETE_TRANSACTION));
        }
    }

    @PutMapping(TRANSACTION_SET_LABELS_ROUTE)
    public Transaction setLabels(@NotNull @PathVariable Long transactionId,
                                 @NotNull @RequestBody List<Long> labels) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        this.notificationService.inform(this.transactionDaoJpa.setLabels(username, transactionId, labels));
        return getTransaction(transactionId);
    }

    @PutMapping(TRANSACTION_SET_COLOR_ROUTE)
    public Transaction setColor(@NotNull @PathVariable Long transactionId, @RequestBody Optional<String> color) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        this.transactionDaoJpa.setColor(username, transactionId, color.orElse(null));
        return getTransaction(transactionId);
    }

    @PutMapping(TRANSACTION_SET_BANK_ACCOUNT_ROUTE)
    public Transaction setBankAccount(@NotNull @PathVariable Long transactionId,
                                @RequestBody Optional<String> bankAccount) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        this.transactionDaoJpa.setBankAccount(username, transactionId,
                bankAccount.isPresent() ? Long.parseLong(bankAccount.get()) : null);
        return getTransaction(transactionId);
    }

    @PostMapping(MOVE_TRANSACTION_ROUTE)
    public void moveTransaction(@NotNull @PathVariable Long transactionId,
                                @NotNull @RequestBody MoveProjectItemParams moveProjectItemParams) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        Pair<com.bulletjournal.repository.models.Transaction, com.bulletjournal.repository.models.Project> res = this.transactionDaoJpa
                .move(username, transactionId, moveProjectItemParams.getTargetProject());
        com.bulletjournal.repository.models.Transaction transaction = res.getLeft();
        com.bulletjournal.repository.models.Project targetProject = res.getRight();
        this.notificationService.trackActivity(new Auditable(transaction.getProject().getId(),
                "moved Transaction ##" + transaction.getName() + "## to BuJo ##"
                        + targetProject.getName() + "##",
                username, transaction.getId(), Timestamp.from(Instant.now()),
                ContentAction.MOVE_TRANSACTION));
    }

    @Deprecated
    @PostMapping(SHARE_TRANSACTION_ROUTE)
    public String shareTransaction(@NotNull @PathVariable Long transactionId,
                                   @NotNull @RequestBody ShareProjectItemParams shareProjectItemParams) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        Informed inform = this.transactionDaoJpa.shareProjectItem(transactionId, shareProjectItemParams,
                username);
        this.notificationService.inform(inform);
        return null; // may be generated link
    }

    private Pair<ZonedDateTime, ZonedDateTime> getStartEndTime(FrequencyType frequencyType, String timezone,
                                                               String startDate, String endDate) {
        ZonedDateTime startTime;
        ZonedDateTime endTime;
        if (StringUtils.isBlank(startDate) || StringUtils.isBlank(endDate)) {
            if (frequencyType == null) {
                throw new BadRequestException("Missing FrequencyType");
            }
            startTime = ZonedDateTimeHelper.getStartTime(frequencyType, timezone);
            endTime = ZonedDateTimeHelper.getEndTime(frequencyType, timezone);
        } else {
            startTime = ZonedDateTimeHelper.getStartTime(startDate, null, timezone);
            endTime = ZonedDateTimeHelper.getEndTime(endDate, null, timezone);
        }
        return Pair.of(startTime, endTime);
    }

    @PostMapping(ADD_CONTENT_ROUTE)
    public Content addContent(@NotNull @PathVariable Long transactionId,
                              @NotNull @RequestBody CreateContentParams createContentParams) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        Pair<ContentModel, ProjectItemModel> res = this.transactionDaoJpa.addContent(transactionId, username,
                new TransactionContent(createContentParams.getText()));

        Content createdContent = res.getLeft().toPresentationModel();
        String transactionName = res.getRight().getName();
        Long projectId = res.getRight().getProject().getId();
        String projectName = res.getRight().getProject().getName();

        this.notificationService.trackActivity(new Auditable(projectId,
                "created Content in Transaction ##" + transactionName + "## under BuJo ##" + projectName
                        + "##",
                username, transactionId, Timestamp.from(Instant.now()),
                ContentAction.ADD_TRANSACTION_CONTENT));

        return createdContent;
    }

    @GetMapping(CONTENTS_ROUTE)
    public List<Content> getContents(@NotNull @PathVariable Long transactionId) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        return Content.addOwnerAvatar(
                this.transactionDaoJpa.getContents(transactionId, username).stream()
                        .map(t -> t.toPresentationModel()).collect(Collectors.toList()),
                this.userClient);
    }

    @DeleteMapping(CONTENT_ROUTE)
    public List<Content> deleteContent(@NotNull @PathVariable Long transactionId,
                                       @NotNull @PathVariable Long contentId) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        List<String> deleteESDocumentIds = this.transactionDaoJpa.getDeleteESDocumentIdsForContent(username, contentId);

        ProjectItemModel transaction = this.transactionDaoJpa.deleteContent(contentId, transactionId, username);

        this.notificationService.trackActivity(new Auditable(transaction.getProject().getId(),
                "deleted Content in Transaction ##" + transaction.getName() + "## under BuJo ##"
                        + transaction.getProject().getName() + "##",
                username, transactionId, Timestamp.from(Instant.now()),
                ContentAction.DELETE_TRANSACTION_CONTENT));
        this.notificationService.deleteESDocument(new RemoveElasticsearchDocumentEvent(deleteESDocumentIds));

        return getContents(transactionId);
    }

    @PatchMapping(CONTENT_ROUTE)
    public List<Content> updateContent(@NotNull @PathVariable Long transactionId,
                                       @NotNull @PathVariable Long contentId,
                                       @NotNull @RequestBody UpdateContentParams updateContentParams, @RequestHeader(IF_NONE_MATCH) Optional<String> etag) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        ProjectItemModel transaction = this.transactionDaoJpa
                .updateContent(contentId, transactionId, username, updateContentParams, etag).getRight();

        this.notificationService.trackActivity(new Auditable(transaction.getProject().getId(),
                "updated Content in Transaction ##" + transaction.getName() + "## under BuJo ##"
                        + transaction.getProject().getName() + "##",
                username, transactionId, Timestamp.from(Instant.now()),
                ContentAction.UPDATE_TRANSACTION_CONTENT));

        return getContents(transactionId);
    }

    @GetMapping(CONTENT_REVISIONS_ROUTE)
    public Revision getContentRevision(@NotNull @PathVariable Long transactionId,
                                       @NotNull @PathVariable Long contentId, @NotNull @PathVariable Long revisionId) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        Revision revision = this.transactionDaoJpa.getContentRevision(username, transactionId, contentId,
                revisionId);
        return Revision.addAvatar(revision, this.userClient);
    }

    @PostMapping(SET_CONTENTS_ORDER_ROUTE)
    public void setContentsOrder(@NotNull @PathVariable Long transactionId, @NotNull @RequestBody List<Long> order) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        this.transactionDaoJpa.setContentsOrder(username, transactionId, order);
    }

    @PostMapping(TRANSACTION_EXPORT_EMAIL_ROUTE)
    public void exportTransactionAsEmail(
            @NotNull @PathVariable Long transactionId,
            @NotNull @RequestBody ExportProjectItemAsEmailParams params) {
        String username = MDC.get(UserClient.USER_NAME_KEY);

        Set<String> targetEmails =
            groupDaoJpa.getEmails(params.getTargetGroup(), params.getTargetUser());
        targetEmails.addAll(params.getEmails());

        com.bulletjournal.repository.models.Transaction transaction =
            transactionDaoJpa.getProjectItem(transactionId, username);
        try {
            String html = freeMarkerClient.convertProjectItemIntoHtmlString(transaction, username, params.getContents());
            String emailSubject = username + " is sharing transaction <" +  transaction.getName() + "> with you.";
            messagingService.sendExportedHtmlContentEmailToUsers(emailSubject, html, targetEmails);
        }
        catch (IOException | TemplateException e) {
            LOGGER.error("Failed to convert transaction into HTML string");
        }
    }

    @PostMapping(TRANSACTION_EXPORT_PDF_ROUTE)
    public ResponseEntity<Object> exportTransactionAsPdf(
            @NotNull @PathVariable Long transactionId, @NotNull @RequestBody ExportProjectItemParams params) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        com.bulletjournal.repository.models.Transaction transaction = transactionDaoJpa.getProjectItem(transactionId, username);
        try {
            String html = freeMarkerClient.convertProjectItemIntoPdfHtml(transaction, params.getContents());
            ByteArrayResource resource = OpenHtmlConverter.projectItemHtmlToPdf(html);

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=transaction.pdf");
            return ResponseEntity.status(HttpStatus.OK)
                    .headers(headers)
                    .contentLength(resource.contentLength())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        }
        catch (IOException | TemplateException e) {
            LOGGER.error("Failed to convert transaction into HTML string", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to get transaction as PDF.");
        }
    }

    @PostMapping(TRANSACTION_EXPORT_IMAGE_ROUTE)
    public ResponseEntity<Object> exportTransactionAsImage(
            @NotNull @PathVariable Long transactionId, @NotNull @RequestBody ExportProjectItemParams params) {
        String username = MDC.get(UserClient.USER_NAME_KEY);
        com.bulletjournal.repository.models.Transaction transaction = transactionDaoJpa.getProjectItem(transactionId, username);
        try {
            String html = freeMarkerClient.convertProjectItemIntoImageHtml(transaction, params.getContents());
            ByteArrayResource resource;
            if (params.isMobile()) {
                resource = OpenHtmlConverter.projectItemHtmlToImageForMobile(html);
            } else {
                resource = OpenHtmlConverter.projectItemHtmlToImageForPC(html);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=transaction.png");
            return ResponseEntity.status(HttpStatus.OK)
                    .headers(headers)
                    .contentLength(resource.contentLength())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        }
        catch (Exception e) {
            LOGGER.error("Failed to convert transaction into HTML string", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to get transaction as image.");
        }
    }
}
