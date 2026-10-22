package com.cobug.projects;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/projects/{projectId}/roadmap")
public class RoadmapController {
    private final RoadmapStationRepository stations;
    private final RoadmapPointRepository points;
    private final ProjectAccessService access;

    public RoadmapController(RoadmapStationRepository stations, RoadmapPointRepository points, ProjectAccessService access) {
        this.stations = stations;
        this.points = points;
        this.access = access;
    }

    public record StationRequest(String title, String description, UUID insertAfterStationId, String status) {}
    public record PointRequest(String title, String details, String status) {}

    @GetMapping
    public Map<String, Object> get(@PathVariable UUID projectId, Authentication auth) {
        access.requireMember(projectId, auth.getName());
        List<RoadmapStation> list = stations.findByProjectIdOrderByPositionAsc(projectId);
        List<Map<String,Object>> result = list.stream().map(this::stationMap).toList();
        long total = list.size();
        long done = list.stream().filter(s -> "DONE".equalsIgnoreCase(s.getStatus())).count();
        int progress = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        return Map.of("stations", result, "totalStations", total, "completedStations", done, "progress", progress);
    }

    @PostMapping("/stations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createStation(@PathVariable UUID projectId, @RequestBody StationRequest request, Authentication auth) {
        access.requireMember(projectId, auth.getName());
        if (request == null || request.title() == null || request.title().isBlank()) throw bad("Station title is required.");
        List<RoadmapStation> list = stations.findByProjectIdOrderByPositionAsc(projectId);
        int insertAt = list.size();
        if (request.insertAfterStationId() != null) {
            int index = -1;
            for (int i=0;i<list.size();i++) if (list.get(i).getId().equals(request.insertAfterStationId())) { index=i; break; }
            if (index < 0) throw notFound("Station to insert after was not found.");
            insertAt = index + 1;
        }
        for (RoadmapStation s : list) if (s.getPosition() >= insertAt) s.setPosition(s.getPosition() + 1);
        if (!list.isEmpty()) stations.saveAll(list);
        RoadmapStation station = new RoadmapStation();
        station.setProjectId(projectId);
        station.setTitle(request.title().trim());
        station.setDescription(clean(request.description()));
        station.setPosition(insertAt);
        station.setStatus(validStatus(request.status()));
        return stationMap(stations.save(station));
    }

    @PatchMapping("/stations/{stationId}")
    public Map<String,Object> updateStation(@PathVariable UUID projectId, @PathVariable UUID stationId, @RequestBody StationRequest request, Authentication auth) {
        access.requireMember(projectId, auth.getName());
        RoadmapStation station = station(projectId, stationId);
        if (request.title() != null && !request.title().isBlank()) station.setTitle(request.title().trim());
        if (request.description() != null) station.setDescription(request.description().trim());
        if (request.status() != null) station.setStatus(validStatus(request.status()));
        return stationMap(stations.save(station));
    }

    @DeleteMapping("/stations/{stationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStation(@PathVariable UUID projectId, @PathVariable UUID stationId, Authentication auth) {
        access.requireMember(projectId, auth.getName());
        RoadmapStation station = station(projectId, stationId);
        points.deleteByStationId(stationId);
        stations.delete(station);
        List<RoadmapStation> remaining = stations.findByProjectIdOrderByPositionAsc(projectId);
        for (int i=0;i<remaining.size();i++) remaining.get(i).setPosition(i);
        stations.saveAll(remaining);
    }

    @PostMapping("/stations/{stationId}/points")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createPoint(@PathVariable UUID projectId, @PathVariable UUID stationId, @RequestBody PointRequest request, Authentication auth) {
        access.requireMember(projectId, auth.getName());
        station(projectId, stationId);
        if (request == null || request.title() == null || request.title().isBlank()) throw bad("Point title is required.");
        int position = points.findByStationIdOrderByPositionAsc(stationId).size();
        RoadmapPoint point = new RoadmapPoint();
        point.setStationId(stationId); point.setTitle(request.title().trim()); point.setDetails(clean(request.details()));
        point.setPosition(position); point.setStatus(validStatus(request.status()));
        return pointMap(points.save(point));
    }

    @PatchMapping("/stations/{stationId}/points/{pointId}")
    public Map<String,Object> updatePoint(@PathVariable UUID projectId, @PathVariable UUID stationId, @PathVariable UUID pointId, @RequestBody PointRequest request, Authentication auth) {
        access.requireMember(projectId, auth.getName());
        station(projectId, stationId);
        RoadmapPoint point = points.findById(pointId).filter(p -> p.getStationId().equals(stationId)).orElseThrow(() -> notFound("Roadmap point not found."));
        if (request.title() != null && !request.title().isBlank()) point.setTitle(request.title().trim());
        if (request.details() != null) point.setDetails(request.details().trim());
        if (request.status() != null) point.setStatus(validStatus(request.status()));
        return pointMap(points.save(point));
    }

    @DeleteMapping("/stations/{stationId}/points/{pointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePoint(@PathVariable UUID projectId, @PathVariable UUID stationId, @PathVariable UUID pointId, Authentication auth) {
        access.requireMember(projectId, auth.getName());
        station(projectId, stationId);
        RoadmapPoint point = points.findById(pointId).filter(p -> p.getStationId().equals(stationId)).orElseThrow(() -> notFound("Roadmap point not found."));
        points.delete(point);
    }

    private RoadmapStation station(UUID projectId, UUID stationId) {
        return stations.findById(stationId).filter(s -> s.getProjectId().equals(projectId)).orElseThrow(() -> notFound("Roadmap station not found."));
    }
    private Map<String,Object> stationMap(RoadmapStation s) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id", s.getId()); m.put("title", s.getTitle()); m.put("description", s.getDescription()); m.put("position", s.getPosition()); m.put("status", s.getStatus());
        m.put("points", points.findByStationIdOrderByPositionAsc(s.getId()).stream().map(this::pointMap).toList());
        return m;
    }
    private Map<String,Object> pointMap(RoadmapPoint p) {
        Map<String,Object> m = new LinkedHashMap<>(); m.put("id",p.getId()); m.put("title",p.getTitle()); m.put("details",p.getDetails()); m.put("position",p.getPosition()); m.put("status",p.getStatus()); return m;
    }
    private String validStatus(String s) { String v=s==null?"TODO":s.toUpperCase(Locale.ROOT); return Set.of("TODO","DONE").contains(v)?v:"TODO"; }
    private String clean(String s) { return s == null ? "" : s.trim(); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException notFound(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
