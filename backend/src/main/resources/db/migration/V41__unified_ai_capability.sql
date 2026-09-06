ALTER TABLE ai_model_capability
    ADD COLUMN tool_image_continuation_supported BOOLEAN NOT NULL DEFAULT FALSE
        AFTER continuous_tools_supported;
