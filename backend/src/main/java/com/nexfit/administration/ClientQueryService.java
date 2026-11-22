package com.nexfit.administration;

import com.nexfit.administration.internal.ClientContactRepository;
import com.nexfit.administration.internal.ClientQueryRepository;
import com.nexfit.audit.AuditEventType;
import com.nexfit.audit.AuditService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Búsqueda y consulta de clientes del gimnasio (US-15, NEX-43). La matriz CLIENTES da CONSULTAR también al
 * Entrenador y al Cliente, pero la búsqueda general es solo de Staff y Administrador: el servicio lo exige
 * y responde 403 a los demás. El listado es básico (identidad, estado de afiliación y membresía); el detalle
 * muestra EPS y contactos de emergencia y por eso se audita (D-08, RF-105).
 */
@Service
class ClientQueryService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final ClientQueryRepository repo;
    private final ClientContactRepository contactRepo;
    private final AuditService audit;

    ClientQueryService(ClientQueryRepository repo, ClientContactRepository contactRepo, AuditService audit) {
        this.repo = repo;
        this.contactRepo = contactRepo;
        this.audit = audit;
    }

    public enum Status {
        OK, FORBIDDEN, NOT_FOUND
    }

    public record ListResult(Status status, Page page) { }

    public record Page(List<ClientQueryRepository.Summary> content, int page, int size, long totalElements,
                       int totalPages) { }

    public record DetailResult(Status status, Detail detail) { }

    public record Detail(ClientQueryRepository.Detail client, List<ClientContactRepository.Contact> emergencyContacts) { }

    @Transactional(readOnly = true)
    public ListResult list(long orgId, long actorId, String query, Boolean active, int page, int size) {
        if (!repo.isStaffOrAdmin(orgId, actorId)) {
            return new ListResult(Status.FORBIDDEN, null);
        }
        int pageSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int pageIndex = Math.max(page, 0);
        var result = repo.search(orgId, tokens(query), active, pageSize, (long) pageIndex * pageSize);
        int totalPages = (int) ((result.total() + pageSize - 1) / pageSize);
        return new ListResult(Status.OK, new Page(result.content(), pageIndex, pageSize, result.total(), totalPages));
    }

    // Sin readOnly: la consulta audita con un INSERT, igual que el contacto de la NEX-42.
    @Transactional
    public DetailResult detail(long orgId, long actorId, long memberId, String ip) {
        if (!repo.isStaffOrAdmin(orgId, actorId)) {
            return new DetailResult(Status.FORBIDDEN, null);
        }
        var client = repo.findDetail(orgId, memberId);
        if (client.isEmpty()) {
            return new DetailResult(Status.NOT_FOUND, null);
        }
        audit.record(AuditEventType.HEALTH_CONTACT_DATA_ACCESSED, actorId, orgId,
            "organization_member", memberId, ip, null);
        return new DetailResult(Status.OK, new Detail(client.get(), contactRepo.contacts(orgId, memberId)));
    }

    /** Fragmentos de la búsqueda, escapados para ILIKE: cada palabra debe aparecer en el nombre o el documento. */
    static List<String> tokens(String query) {
        List<String> tokens = new ArrayList<>();
        if (query == null) {
            return tokens;
        }
        for (String raw : query.trim().split("\\s+")) {
            if (!raw.isEmpty()) {
                tokens.add(raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
            }
        }
        return tokens;
    }
}
