package com.research.assistant.service.impl;

import com.research.assistant.entity.Paper;
import com.research.assistant.entity.Tag;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.TagMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TagServiceImplTest {

    @Test
    void validatesPaperAndTagReferencesBeforeReplacingLinks() {
        TagMapper tagMapper = mock(TagMapper.class);
        PaperMapper paperMapper = mock(PaperMapper.class);
        TagServiceImpl service = new TagServiceImpl(tagMapper, paperMapper);
        Paper paper = new Paper();
        paper.setId(3L);
        Tag tag = new Tag();
        tag.setId(8L);
        when(paperMapper.selectById(3L)).thenReturn(paper);
        when(tagMapper.selectById(8L)).thenReturn(tag);

        service.setPaperTags(3L, List.of(8L, 8L));

        verify(tagMapper).deletePaperTagsByPaperId(3L);
        verify(tagMapper).insertPaperTag(3L, 8L);
    }

    @Test
    void rejectsMissingTagWithoutDeletingExistingLinks() {
        TagMapper tagMapper = mock(TagMapper.class);
        PaperMapper paperMapper = mock(PaperMapper.class);
        TagServiceImpl service = new TagServiceImpl(tagMapper, paperMapper);
        when(paperMapper.selectById(3L)).thenReturn(new Paper());
        when(tagMapper.selectById(8L)).thenReturn(null);

        assertThatThrownBy(() -> service.setPaperTags(3L, List.of(8L)))
                .hasMessage("标签不存在");
        verify(tagMapper, never()).deletePaperTagsByPaperId(anyLong());
    }
}
