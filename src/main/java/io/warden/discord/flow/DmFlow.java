package io.warden.discord.flow;

import io.warden.discord.WardenEmbeds;
import io.warden.onboarding.OnboardingService.StepResult;
import io.warden.onboarding.model.Question;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure JDA-rendering layer for the onboarding DM flow.
 * No DB access, no state mutation - just message/component builders.
 *
 * Custom-id grammar used here:
 *   onboard:agree
 *   onboard:disagree
 *   onboard:open:&lt;questionId&gt;    button → opens modal for a text question
 *   onboard:modal:&lt;questionId&gt;   modal submission custom-id
 *   onboard:choice:&lt;questionId&gt;  string-select for single/multi choice
 *
 * Text-input id inside modals is always "value" so the modal listener can
 * fetch it without knowing the question kind.
 */
public final class DmFlow {

    public static final Color BRAND_COLOR = new Color(0x6B, 0x83, 0xFF); // Warden brand blue
    public static final String MODAL_VALUE_ID = "value";

    private DmFlow() {}

    public static MessageCreateData rules(String rulesMarkdown, String guildName) {
        String body = (rulesMarkdown == null || rulesMarkdown.isBlank())
                ? "_The server admin has not set rules text yet._"
                : rulesMarkdown;
        // Embed description is capped at 4096; warn-only truncate if someone wrote a novel.
        if (body.length() > 4090) body = body.substring(0, 4090) + "…";

        MessageEmbed embed = WardenEmbeds.brand(new EmbedBuilder()
                .setTitle("Server rules")
                .setDescription(body)
                .setColor(BRAND_COLOR)
                .setFooter("Click " + emojiOrFallback("✅") + " to continue, or "
                        + emojiOrFallback("❓") + " if you have questions for the mods."))
                .build();

        return new MessageCreateBuilder()
                .setEmbeds(embed)
                .setComponents(ActionRow.of(
                        Button.success("onboard:agree", "I agree, continue"),
                        Button.secondary("onboard:disagree", "I have questions")
                ))
                .build();
    }

    public static MessageCreateData askQuestion(Question q, int answered, int total) {
        String header = "**Question " + (answered + 1) + " / " + total + "**\n" + q.prompt();
        MessageCreateBuilder mb = new MessageCreateBuilder().setContent(header);

        switch (q.kind()) {
            case SHORT_TEXT, LONG_TEXT -> mb.setComponents(ActionRow.of(
                    Button.primary("onboard:open:" + q.id(), "Answer")
            ));
            case SINGLE_CHOICE -> mb.setComponents(stringSelect(q, false));
            case MULTI_CHOICE -> mb.setComponents(stringSelect(q, true));
        }
        return mb.build();
    }

    private static ActionRow stringSelect(Question q, boolean multi) {
        StringSelectMenu.Builder b = StringSelectMenu.create("onboard:choice:" + q.id())
                .setPlaceholder(multi ? "Pick one or more…" : "Pick one…")
                .setMinValues(q.required() ? 1 : 0)
                .setMaxValues(multi ? Math.min(q.choices().size(), 25) : 1);
        List<SelectOption> opts = new ArrayList<>();
        int i = 0;
        for (String c : q.choices()) {
            if (i++ >= 25) break; // discord cap
            // value = label, both clipped to discord limits (100 / 100)
            String label = c.length() > 100 ? c.substring(0, 99) + "…" : c;
            opts.add(SelectOption.of(label, label));
        }
        b.addOptions(opts);
        return ActionRow.of(b.build());
    }

    public static Modal answerModal(Question q) {
        TextInputStyle style = q.kind() == io.warden.onboarding.model.QuestionKind.LONG_TEXT
                ? TextInputStyle.PARAGRAPH
                : TextInputStyle.SHORT;
        TextInput.Builder ib = TextInput.create(MODAL_VALUE_ID, "Your answer", style)
                .setRequired(q.required())
                .setMaxLength(q.kind() == io.warden.onboarding.model.QuestionKind.LONG_TEXT ? 4000 : 280);
        if (q.kind() == io.warden.onboarding.model.QuestionKind.LONG_TEXT) {
            ib.setPlaceholder("A sentence or two is fine.");
        }
        TextInput input = ib.build();
        return Modal.create("onboard:modal:" + q.id(),
                        clip("Q: " + q.prompt(), 45))   // modal titles capped at 45 chars
                .addComponents(ActionRow.of(input))
                .build();
    }

    public static MessageCreateData submittedSummary(long applicationId, String triageNote) {
        MessageEmbed embed = WardenEmbeds.brand(new EmbedBuilder()
                .setTitle("Submitted - thanks!")
                .setDescription(triageNote != null && !triageNote.isBlank()
                        ? triageNote
                        : "Your answers are in. You'll hear back once a moderator has reviewed them.")
                .setColor(BRAND_COLOR)
                .setFooter("Application #" + applicationId))
                .build();
        return new MessageCreateBuilder().setEmbeds(embed).build();
    }

    public static MessageCreateData alreadyDone(String state) {
        String body = switch (state) {
            case "approved" -> "You're already approved - no action needed. Welcome back!";
            case "denied"   -> "Your previous application was not approved. Please contact a moderator if you'd like to discuss.";
            default          -> "You're already partway through onboarding ("
                    + io.warden.onboarding.OnboardingState.labelFromWire(state).toLowerCase() + ").";
        };
        return new MessageCreateBuilder().setContent(body).build();
    }

    public static MessageCreateData stopped(String reason) {
        String body = switch (reason) {
            case "user_declined_rules" -> "No worries - let us know in #mods or DM a mod if you'd like to chat through the rules first.";
            default                    -> "Onboarding stopped.";
        };
        return new MessageCreateBuilder().setContent(body).build();
    }

    public static MessageCreateData notFound() {
        return new MessageCreateBuilder()
                .setContent("Hmm, I don't have a record of you yet. Try leaving and rejoining the server, or ask a mod for help.")
                .build();
    }

    public static MessageCreateData invalidAnswer(Question q, String reason) {
        String hint = switch (reason) {
            case "required"          -> "That question is required - give it another go.";
            case "too_long_280"      -> "That's a bit long (max 280 characters). Could you shorten it?";
            case "too_long_4000"     -> "That's over the 4000-character limit. Tighten it up a bit.";
            case "expected_text"     -> "I expected a text answer there.";
            case "expected_choice"   -> "I expected a choice from the list.";
            case "not_in_choices"    -> "That option isn't in the list - pick one of the offered choices.";
            case "unknown_question"  -> "That question is no longer active. Try /warden reonboard if you're stuck.";
            default                  -> "That answer wasn't accepted - please try again.";
        };
        return new MessageCreateBuilder().setContent("⚠️ " + hint).build();
    }

    /** Render a step into a (possibly empty) list of follow-up DM messages. */
    public static List<MessageCreateData> render(StepResult step, String guildName) {
        return switch (step) {
            case StepResult.ShowRules s         -> List.of(rules(s.rulesMarkdown(), guildName));
            case StepResult.AskQuestion q       -> List.of(askQuestion(q.question(), q.answered(), q.total()));
            case StepResult.Submitted s         -> List.of(submittedSummary(s.applicationId(), null));
            case StepResult.AlreadyDone a       -> List.of(alreadyDone(a.state()));
            case StepResult.Stopped st          -> List.of(stopped(st.reason()));
            case StepResult.NotFound n          -> List.of(notFound());
            case StepResult.InvalidAnswer i     -> List.of(invalidAnswer(i.question(), i.reason()));
        };
    }

    /** Some Windows JVMs misrender emoji in console; in messages they're fine, but keep a safe fallback. */
    private static String emojiOrFallback(String emoji) {
        return emoji;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // Unused but kept so future surfaces (e.g., a re-onboard command) can return raw components.
    @SuppressWarnings("unused")
    private static LayoutComponent navigationRow() {
        return ActionRow.of(Button.danger("onboard:abandon", "Cancel"));
    }
}
