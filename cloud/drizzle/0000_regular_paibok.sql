CREATE TABLE `rain_login_attempts` (
	`user_id` text PRIMARY KEY NOT NULL,
	`attempt_id` text NOT NULL,
	`updated_at` integer NOT NULL
);
--> statement-breakpoint
CREATE TABLE `rain_sessions` (
	`user_id` text PRIMARY KEY NOT NULL,
	`region` text NOT NULL,
	`secret` text NOT NULL,
	`updated_at` integer NOT NULL
);
