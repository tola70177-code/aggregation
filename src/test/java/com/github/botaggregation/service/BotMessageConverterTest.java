package com.github.botaggregation.service;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotMessageConverterTest {

    private Message textMessage(String text, List<MessageEntity> entities) {
        Message msg = mock(Message.class);
        when(msg.getText()).thenReturn(text);
        when(msg.getCaption()).thenReturn(null);
        when(msg.getEntities()).thenReturn(entities);
        when(msg.getCaptionEntities()).thenReturn(null);
        return msg;
    }

    private Message captionMessage(String caption, List<MessageEntity> entities) {
        Message msg = mock(Message.class);
        when(msg.getText()).thenReturn(null);
        when(msg.getCaption()).thenReturn(caption);
        when(msg.getEntities()).thenReturn(null);
        when(msg.getCaptionEntities()).thenReturn(entities);
        return msg;
    }

    private MessageEntity entity(String type, int offset, int length) {
        MessageEntity e = mock(MessageEntity.class);
        when(e.getType()).thenReturn(type);
        when(e.getOffset()).thenReturn(offset);
        when(e.getLength()).thenReturn(length);
        when(e.getUrl()).thenReturn(null);
        return e;
    }

    private MessageEntity linkEntity(int offset, int length, String url) {
        MessageEntity e = mock(MessageEntity.class);
        when(e.getType()).thenReturn("text_link");
        when(e.getOffset()).thenReturn(offset);
        when(e.getLength()).thenReturn(length);
        when(e.getUrl()).thenReturn(url);
        return e;
    }

    @Test
    void toHtml_plainText_returnsUnchanged() {
        assertThat(BotMessageConverter.toHtml(textMessage("Hello world", List.of())))
                .isEqualTo("Hello world");
    }

    @Test
    void toHtml_escapesHtmlCharacters() {
        assertThat(BotMessageConverter.toHtml(textMessage("a < b & c > d", List.of())))
                .isEqualTo("a &lt; b &amp; c &gt; d");
    }

    @Test
    void toHtml_boldText() {
        assertThat(BotMessageConverter.toHtml(textMessage("Hello world", List.of(
                entity("bold", 0, 5)
        )))).isEqualTo("<b>Hello</b> world");
    }

    @Test
    void toHtml_italicText() {
        assertThat(BotMessageConverter.toHtml(textMessage("Hello world", List.of(
                entity("italic", 6, 5)
        )))).isEqualTo("Hello <i>world</i>");
    }

    @Test
    void toHtml_underlineText() {
        assertThat(BotMessageConverter.toHtml(textMessage("Hello world", List.of(
                entity("underline", 0, 5)
        )))).isEqualTo("<u>Hello</u> world");
    }

    @Test
    void toHtml_strikethroughText() {
        assertThat(BotMessageConverter.toHtml(textMessage("Hello world", List.of(
                entity("strikethrough", 0, 5)
        )))).isEqualTo("<s>Hello</s> world");
    }

    @Test
    void toHtml_codeText() {
        assertThat(BotMessageConverter.toHtml(textMessage("use code here", List.of(
                entity("code", 4, 4)
        )))).isEqualTo("use <code>code</code> here");
    }

    @Test
    void toHtml_preText() {
        assertThat(BotMessageConverter.toHtml(textMessage("code block", List.of(
                entity("pre", 0, 10)
        )))).isEqualTo("<pre>code block</pre>");
    }

    @Test
    void toHtml_spoilerText() {
        assertThat(BotMessageConverter.toHtml(textMessage("hidden text", List.of(
                entity("spoiler", 0, 6)
        )))).isEqualTo("<tg-spoiler>hidden</tg-spoiler> text");
    }

    @Test
    void toHtml_textLink() {
        assertThat(BotMessageConverter.toHtml(textMessage("click here", List.of(
                linkEntity(0, 10, "https://example.com")
        )))).isEqualTo("<a href=\"https://example.com\">click here</a>");
    }

    @Test
    void toHtml_multipleEntities() {
        assertThat(BotMessageConverter.toHtml(textMessage("bold and italic", List.of(
                entity("bold", 0, 4),
                entity("italic", 9, 6)
        )))).isEqualTo("<b>bold</b> and <i>italic</i>");
    }

    @Test
    void toHtml_nestedEntities_outerBoldInnerItalic() {
        assertThat(BotMessageConverter.toHtml(textMessage("Hello world", List.of(
                entity("bold", 0, 11),
                entity("italic", 0, 5)
        )))).isEqualTo("<b><i>Hello</i> world</b>");
    }

    @Test
    void toHtml_nonFormattingEntitiesIgnored() {
        assertThat(BotMessageConverter.toHtml(textMessage("Visit https://example.com #tag", List.of(
                entity("url", 6, 19),
                entity("hashtag", 26, 4)
        )))).isEqualTo("Visit https://example.com #tag");
    }

    @Test
    void toHtml_nullTextAndCaption_returnsEmpty() {
        Message msg = mock(Message.class);
        when(msg.getText()).thenReturn(null);
        when(msg.getCaption()).thenReturn(null);
        when(msg.getEntities()).thenReturn(null);
        when(msg.getCaptionEntities()).thenReturn(null);
        assertThat(BotMessageConverter.toHtml(msg)).isEmpty();
    }

    @Test
    void toHtml_captionFallback() {
        assertThat(BotMessageConverter.toHtml(captionMessage("Photo caption", List.of(
                entity("bold", 0, 5)
        )))).isEqualTo("<b>Photo</b> caption");
    }

    @Test
    void toHtml_htmlInsideFormattedEntity_escaped() {
        String result = BotMessageConverter.toHtml(textMessage("<b>not bold</b>", List.of()));
        assertThat(result).isEqualTo("&lt;b&gt;not bold&lt;/b&gt;");
    }
}
