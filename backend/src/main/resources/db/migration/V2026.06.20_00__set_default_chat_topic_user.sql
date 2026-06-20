-- Single-user app: reassign all existing chats to the configured user.
-- ${default_user} is a Flyway placeholder bound to kb.security.username
-- (spring.flyway.placeholders.default_user), defaulting to 'admin'.
update chat_topic
set "user" = '${default_user}'
where "user" <> '${default_user}';
