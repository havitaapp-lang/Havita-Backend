package com.havita.modules.assembly.api;

import com.havita.common.api.ApiResponse;
import com.havita.common.api.PageResponse;
import com.havita.modules.assembly.api.dto.AssemblyResponse;
import com.havita.modules.assembly.api.dto.AttendeeResponse;
import com.havita.modules.assembly.api.dto.CastVoteRequest;
import com.havita.modules.assembly.api.dto.CreateAssemblyRequest;
import com.havita.modules.assembly.api.dto.CreatePollRequest;
import com.havita.modules.assembly.api.dto.PollResponse;
import com.havita.modules.assembly.api.dto.RegisterAttendeeRequest;
import com.havita.modules.assembly.application.AssemblyService;
import com.havita.modules.assembly.application.PollService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assemblies")
@RequiredArgsConstructor
@Tag(name = "Assemblies & Voting", description = "Gestión de asambleas generales, registro de quórum y votaciones ponderadas en vivo")
public class AssemblyController {

    private final AssemblyService assemblyService;
    private final PollService pollService;

    // --- Asambleas ---

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Listar asambleas")
    public ResponseEntity<ApiResponse<PageResponse<AssemblyResponse>>> findAll(
            @org.springframework.web.bind.annotation.RequestParam(required = false) com.havita.modules.assembly.domain.AssemblyStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AssemblyResponse> page = assemblyService.findAll(status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(page)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Obtener asamblea por ID")
    public ResponseEntity<ApiResponse<AssemblyResponse>> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(assemblyService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Programar nueva asamblea")
    public ResponseEntity<ApiResponse<AssemblyResponse>> create(
            @Valid @RequestBody CreateAssemblyRequest request) {
        AssemblyResponse response = assemblyService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Asamblea programada exitosamente"));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Iniciar asamblea (IN_PROGRESS)")
    public ResponseEntity<ApiResponse<AssemblyResponse>> startAssembly(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(assemblyService.startAssembly(id), "Asamblea iniciada"));
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Finalizar asamblea")
    public ResponseEntity<ApiResponse<AssemblyResponse>> finishAssembly(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(assemblyService.finishAssembly(id), "Asamblea finalizada"));
    }

    // --- Quórum y Asistencia ---

    @GetMapping("/{id}/attendees")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Listar lista de asistencia y quórum de la asamblea")
    public ResponseEntity<ApiResponse<List<AttendeeResponse>>> findAttendees(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(assemblyService.findAttendees(id)));
    }

    @GetMapping("/{id}/voting-rights")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Obtener lista de propiedades con derecho a voto (propietario o delegado)")
    public ResponseEntity<ApiResponse<List<com.havita.modules.assembly.api.dto.VotingRightResponse>>> getVotingRights(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(assemblyService.getVotingRights(id)));
    }

    @PostMapping("/{id}/attendees")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Registrar asistente en el quórum de la asamblea")
    public ResponseEntity<ApiResponse<AttendeeResponse>> registerAttendee(
            @PathVariable UUID id,
            @Valid @RequestBody RegisterAttendeeRequest request) {
        AttendeeResponse response = assemblyService.registerAttendee(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Asistente registrado en el quórum"));
    }

    @GetMapping("/{id}/attendees/eligible-representatives")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Cierre E5 / D14: personas de una unidad que pueden representarla en la asamblea")
    public ResponseEntity<ApiResponse<List<com.havita.modules.assembly.api.dto.EligibleRepresentativeResponse>>> findEligibleRepresentatives(
            @PathVariable UUID id, @org.springframework.web.bind.annotation.RequestParam UUID propertyId) {
        return ResponseEntity.ok(ApiResponse.ok(assemblyService.findEligibleRepresentatives(id, propertyId)));
    }

    @org.springframework.web.bind.annotation.PutMapping("/{id}/attendees/{attendeeId}/representative")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Cierre E5 / D14: asignar el representante de una asistencia sin persona identificada")
    public ResponseEntity<ApiResponse<AttendeeResponse>> assignRepresentative(
            @PathVariable UUID id, @PathVariable UUID attendeeId,
            @Valid @RequestBody com.havita.modules.assembly.api.dto.AssignRepresentativeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(assemblyService.assignRepresentative(id, attendeeId, request.residentId()),
                "Representante asignado"));
    }

    @GetMapping("/{id}/my-attendance-units")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Fase 2 / E5: unidades del residente y su estado de asistencia en la asamblea",
            description = "Solo las unidades ACTIVE del residente en esta comunidad. status: REGISTERED, PENDING, NOT_ELIGIBLE (reasonCode) o CLOSED.")
    public ResponseEntity<ApiResponse<List<com.havita.modules.assembly.api.dto.MyAttendanceUnitResponse>>> findMyAttendanceUnits(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(assemblyService.findMyAttendanceUnits(id)));
    }

    @PostMapping("/{id}/attendees/self-register")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Auto-registrar asistencia de un residente a la asamblea")
    public ResponseEntity<ApiResponse<AttendeeResponse>> selfRegisterAttendee(
            @PathVariable UUID id,
            @Valid @RequestBody com.havita.modules.assembly.api.dto.SelfRegisterAttendeeRequest request) {
        AttendeeResponse response = assemblyService.selfRegisterAttendee(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Asistencia registrada exitosamente en el quórum"));
    }

    // --- Votaciones (Polls) ---

    @GetMapping("/{id}/polls")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Listar votaciones de una asamblea")
    public ResponseEntity<ApiResponse<List<PollResponse>>> findPollsByAssembly(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(pollService.findByAssembly(id)));
    }

    @GetMapping("/polls")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Listar todas las votaciones de la copropiedad")
    public ResponseEntity<ApiResponse<List<PollResponse>>> findAllPolls() {
        return ResponseEntity.ok(ApiResponse.ok(pollService.findAllByTenant()));
    }

    @GetMapping("/polls/{pollId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Consultar estado y resultados de una votación")
    public ResponseEntity<ApiResponse<PollResponse>> findPollById(@PathVariable UUID pollId) {
        return ResponseEntity.ok(ApiResponse.ok(pollService.findById(pollId)));
    }

    @PostMapping("/polls")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Crear nueva votación / moción")
    public ResponseEntity<ApiResponse<PollResponse>> createPoll(
            @Valid @RequestBody CreatePollRequest request) {
        PollResponse response = pollService.createPoll(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response, "Votación creada exitosamente"));
    }

    @PostMapping("/polls/{pollId}/open")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Abrir votación en vivo (LIVE)")
    public ResponseEntity<ApiResponse<PollResponse>> openPoll(@PathVariable UUID pollId) {
        return ResponseEntity.ok(ApiResponse.ok(pollService.openPoll(pollId), "Votación abierta"));
    }

    @PostMapping("/polls/{pollId}/close")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN')")
    @Operation(summary = "Cerrar votación")
    public ResponseEntity<ApiResponse<PollResponse>> closePoll(@PathVariable UUID pollId) {
        return ResponseEntity.ok(ApiResponse.ok(pollService.closePoll(pollId), "Votación cerrada"));
    }

    /** Fase 2 / E3 (D5): unidades del residente para una pregunta y su estado de voto. */
    @GetMapping("/polls/{pollId}/my-voting-units")
    @PreAuthorize("hasRole('RESIDENT')")
    @Operation(summary = "Mis unidades habilitadas para votar en una pregunta")
    public ResponseEntity<ApiResponse<List<com.havita.modules.assembly.api.dto.MyVotingUnitResponse>>> findMyVotingUnits(
            @PathVariable UUID pollId) {
        return ResponseEntity.ok(ApiResponse.ok(pollService.findMyVotingUnits(pollId)));
    }

    @PostMapping("/polls/{pollId}/vote")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'COMMUNITY_ADMIN', 'RESIDENT')")
    @Operation(summary = "Emitir voto ponderado por coeficiente de copropiedad")
    public ResponseEntity<ApiResponse<PollResponse>> castVote(
            @PathVariable UUID pollId,
            @Valid @RequestBody CastVoteRequest request) {
        PollResponse response = pollService.castVote(pollId, request);
        return ResponseEntity.ok(ApiResponse.ok(response, "Voto registrado exitosamente"));
    }
}
