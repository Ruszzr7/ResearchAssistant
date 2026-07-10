package com.research.assistant.service.note;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.NoteDto;
import com.research.assistant.dto.NoteRequest;
import com.research.assistant.entity.Note;
import com.research.assistant.entity.PaperNoteLink;
import com.research.assistant.mapper.NoteMapper;
import com.research.assistant.mapper.PaperNoteLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 笔记与论文双向链接服务。
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final NoteMapper noteMapper;
    private final PaperNoteLinkMapper linkMapper;
    private final ObjectMapper objectMapper;

    public NoteService(NoteMapper noteMapper, PaperNoteLinkMapper linkMapper, ObjectMapper objectMapper) {
        this.noteMapper = noteMapper;
        this.linkMapper = linkMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询某论文关联的所有笔记。
     */
    public List<NoteDto> listByPaper(Long paperId) {
        List<PaperNoteLink> links = linkMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperNoteLink>()
                        .eq(PaperNoteLink::getPaperId, paperId)
                        .orderByDesc(PaperNoteLink::getCreatedAt));
        return links.stream().map(this::toDto).toList();
    }

    /**
     * 查询某笔记关联的所有论文 ID。
     */
    public List<Long> listPaperIdsByNote(Long noteId) {
        return linkMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperNoteLink>()
                                .eq(PaperNoteLink::getNoteId, noteId)
                                .select(PaperNoteLink::getPaperId))
                .stream()
                .map(PaperNoteLink::getPaperId)
                .distinct()
                .toList();
    }

    /**
     * 在某论文下创建一篇新笔记并建立链接。
     */
    @Transactional
    public NoteDto create(Long paperId, NoteRequest request) {
        Note note = new Note();
        note.setTitle(request.getTitle());
        note.setContent(request.getContent());
        noteMapper.insert(note);

        PaperNoteLink link = new PaperNoteLink();
        link.setPaperId(paperId);
        link.setNoteId(note.getId());
        link.setPage(request.getPage());
        link.setCoordinatesJson(toJson(request.getCoordinates()));
        link.setAnchorText(request.getAnchorText());
        linkMapper.insert(link);

        return toDto(note, link);
    }

    /**
     * 更新笔记内容。
     */
    public NoteDto update(Long noteId, NoteRequest request) {
        Note note = noteMapper.selectById(noteId);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在: " + noteId);
        }
        note.setTitle(request.getTitle());
        note.setContent(request.getContent());
        noteMapper.updateById(note);
        return toDto(note, null);
    }

    /**
     * 删除笔记及其所有链接。
     */
    @Transactional
    public void delete(Long noteId) {
        linkMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperNoteLink>()
                        .eq(PaperNoteLink::getNoteId, noteId));
        noteMapper.deleteById(noteId);
    }

    /**
     * 删除某论文与某笔记的链接；若该笔记不再关联任何论文，则级联删除笔记。
     */
    @Transactional
    public void unlink(Long paperId, Long noteId) {
        linkMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperNoteLink>()
                        .eq(PaperNoteLink::getPaperId, paperId)
                        .eq(PaperNoteLink::getNoteId, noteId));
        long remaining = linkMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PaperNoteLink>()
                        .eq(PaperNoteLink::getNoteId, noteId));
        if (remaining == 0) {
            noteMapper.deleteById(noteId);
        }
    }

    private NoteDto toDto(PaperNoteLink link) {
        Note note = noteMapper.selectById(link.getNoteId());
        if (note == null) return null;
        return toDto(note, link);
    }

    private NoteDto toDto(Note note, PaperNoteLink link) {
        NoteDto dto = new NoteDto();
        dto.setId(note.getId());
        dto.setTitle(note.getTitle());
        dto.setContent(note.getContent());
        dto.setCreatedAt(note.getCreatedAt());
        dto.setUpdatedAt(note.getUpdatedAt());
        if (link != null) {
            dto.setLinkId(link.getId());
            dto.setPage(link.getPage());
            dto.setCoordinates(fromJson(link.getCoordinatesJson()));
            dto.setAnchorText(link.getAnchorText());
        }
        return dto;
    }

    private String toJson(Map<String, Object> coordinates) {
        if (coordinates == null) return "{}";
        try {
            return objectMapper.writeValueAsString(coordinates);
        } catch (JsonProcessingException e) {
            log.warn("坐标 JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            log.warn("坐标 JSON 反序列化失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
