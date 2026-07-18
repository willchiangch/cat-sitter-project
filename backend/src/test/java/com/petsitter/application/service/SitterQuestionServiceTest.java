package com.petsitter.application.service;

import com.petsitter.application.dto.SitterQuestionDto;
import com.petsitter.application.dto.SitterQuestionSortRequest;
import com.petsitter.application.exception.SitterQuestionException;
import com.petsitter.domain.model.User;
import com.petsitter.domain.repository.SitterQuestionRepository;
import com.petsitter.domain.repository.UserRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("local")
@DisplayName("PRD-004: 事前問卷題目管理測試")
class SitterQuestionServiceTest {

    static {
        System.setProperty("com.github.dockerjava.api.version", "1.44");
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SitterQuestionService sitterQuestionService;

    @Autowired
    private SitterQuestionRepository sitterQuestionRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID sitterId;

    @BeforeEach
    void setUp() {
        sitterQuestionRepository.deleteAll();
        userRepository.deleteAll();

        User sitter = userRepository.save(User.builder()
                .email("question-sitter@test.com").passwordHash("hash").fullName("問卷測試保母").role("SITTER").build());
        sitterId = sitter.getId();
    }

    private SitterQuestionDto radioDto(String text, List<String> options) {
        return SitterQuestionDto.builder()
                .questionText(text)
                .answerType("RADIO")
                .options(options)
                .required(true)
                .build();
    }

    @Test
    @DisplayName("可成功建立單選題並正確儲存選項")
    void should_CreateRadioQuestion_Successfully() {
        SitterQuestionDto created = sitterQuestionService.createQuestion(
                sitterId, radioDto("毛孩是否會攻擊人？", List.of("會", "不會")));

        assertThat(created.getId()).isNotNull();
        assertThat(created.getOptions()).containsExactly("會", "不會");
        assertThat(created.isActive()).isTrue();
        assertThat(created.getSortOrder()).isZero();
    }

    @Test
    @DisplayName("單選/多選題沒有選項時應拒絕建立")
    void should_RejectCreate_When_ChoiceTypeHasNoOptions() {
        Assertions.assertThrows(SitterQuestionException.class,
                () -> sitterQuestionService.createQuestion(sitterId, radioDto("測試", List.of())));
    }

    @Test
    @DisplayName("超過題數上限應拒絕建立")
    void should_RejectCreate_When_ExceedsMaxQuestions() {
        for (int i = 0; i < 20; i++) {
            sitterQuestionService.createQuestion(sitterId,
                    SitterQuestionDto.builder().questionText("題目" + i).answerType("INPUT").build());
        }

        Assertions.assertThrows(SitterQuestionException.class,
                () -> sitterQuestionService.createQuestion(sitterId,
                        SitterQuestionDto.builder().questionText("第21題").answerType("INPUT").build()));
    }

    @Test
    @DisplayName("排序調整後應正確持久化 (AC-2)")
    void should_PersistSortOrder_AfterReorder() {
        SitterQuestionDto q1 = sitterQuestionService.createQuestion(sitterId,
                SitterQuestionDto.builder().questionText("Q1").answerType("INPUT").build());
        SitterQuestionDto q2 = sitterQuestionService.createQuestion(sitterId,
                SitterQuestionDto.builder().questionText("Q2").answerType("INPUT").build());

        sitterQuestionService.reorderQuestions(sitterId,
                SitterQuestionSortRequest.builder().questionIds(List.of(q2.getId(), q1.getId())).build());

        List<SitterQuestionDto> reordered = sitterQuestionService.getMyQuestions(sitterId);
        assertThat(reordered.get(0).getId()).isEqualTo(q2.getId());
        assertThat(reordered.get(1).getId()).isEqualTo(q1.getId());
    }

    @Test
    @DisplayName("停用的問題不應出現在預約流程的啟用清單中 (AC-3)")
    void should_ExcludeInactiveQuestions_FromBookingList() {
        SitterQuestionDto q1 = sitterQuestionService.createQuestion(sitterId,
                SitterQuestionDto.builder().questionText("Q1").answerType("INPUT").build());

        sitterQuestionService.toggleActive(sitterId, q1.getId(), false);

        List<SitterQuestionDto> activeForBooking = sitterQuestionService.getActiveQuestionsForBooking(sitterId);
        assertThat(activeForBooking).isEmpty();

        List<SitterQuestionDto> allForManagement = sitterQuestionService.getMyQuestions(sitterId);
        assertThat(allForManagement).hasSize(1);
    }

    @Test
    @DisplayName("刪除問題採邏輯刪除，不會真的從資料庫消失")
    void should_SoftDelete_Question() {
        SitterQuestionDto q1 = sitterQuestionService.createQuestion(sitterId,
                SitterQuestionDto.builder().questionText("Q1").answerType("INPUT").build());

        sitterQuestionService.deleteQuestion(sitterId, q1.getId());

        assertThat(sitterQuestionService.getMyQuestions(sitterId)).isEmpty();
        assertThat(sitterQuestionRepository.findById(q1.getId())).isPresent();
        assertThat(sitterQuestionRepository.findById(q1.getId()).get().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("非本人保母不可操作他人問題 (IDOR 防禦)")
    void should_RejectOperation_When_NotOwner() {
        User otherSitter = userRepository.save(User.builder()
                .email("other-sitter@test.com").passwordHash("hash").fullName("其他保母").role("SITTER").build());

        SitterQuestionDto q1 = sitterQuestionService.createQuestion(sitterId,
                SitterQuestionDto.builder().questionText("Q1").answerType("INPUT").build());

        Assertions.assertThrows(SitterQuestionException.class,
                () -> sitterQuestionService.deleteQuestion(otherSitter.getId(), q1.getId()));
    }
}
