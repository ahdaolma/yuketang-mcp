import {sqliteTable,text,integer} from 'drizzle-orm/sqlite-core';
export const rainSessions=sqliteTable('rain_sessions',{userId:text('user_id').primaryKey(),region:text('region').notNull(),secret:text('secret').notNull(),updatedAt:integer('updated_at').notNull()});
export const rainLoginAttempts=sqliteTable('rain_login_attempts',{userId:text('user_id').primaryKey(),attemptId:text('attempt_id').notNull(),updatedAt:integer('updated_at').notNull()});
