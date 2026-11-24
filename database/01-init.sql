CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE "public"."agent_embeddings"(
        "embedding_id" uuid NOT NULL,
        "embedding" "public"."vector",
        "text" text,
        "metadata" json,
        CONSTRAINT "agent_embeddings_pkey" PRIMARY KEY (embedding_id)
);

CREATE TABLE "public"."conversation"(
        "id" varchar(64) NOT NULL,
        "user_id" varchar(64) NOT NULL,
        "title" varchar(255) NOT NULL DEFAULT '新的会话'::character varying,
        "created_at" timestamptz(6) NOT NULL DEFAULT now(),
        "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
        "is_delete" int2 NOT NULL DEFAULT 0,
        CONSTRAINT "conversation_pkey" PRIMARY KEY (id)
);

CREATE TABLE "public"."message"(
        "id" varchar(64) NOT NULL,
        "conversation_id" varchar(64) NOT NULL,
        "role" int2 NOT NULL,
        "content" text NOT NULL,
        "created_at" timestamptz(6) NOT NULL DEFAULT now(),
        "is_delete" int2 NOT NULL DEFAULT 0,
        CONSTRAINT "message_pkey" PRIMARY KEY (id)
);

COMMENT ON COLUMN "public"."message"."role" IS '1为用户，2为system（AI）';

CREATE INDEX "idx_message_conversation_id" ON "public"."message" USING btree (conversation_id);

CREATE INDEX "idx_message_created_at" ON "public"."message" USING btree (created_at);

CREATE INDEX "idx_message_not_deleted" ON "public"."message" USING btree (id) WHERE (is_delete = 0);

CREATE TABLE "public"."store"(
        "namespace" text[] NOT NULL,
        "key" text NOT NULL,
        "value" jsonb NOT NULL,
        "created_at" timestamp(6) DEFAULT now(),
        "updated_at" timestamp(6) DEFAULT now(),
        CONSTRAINT "store_pkey" PRIMARY KEY (namespace, key)
);

COMMENT ON COLUMN "public"."store"."namespace" IS '命名空间（数组类型）';

COMMENT ON COLUMN "public"."store"."key" IS '键';

COMMENT ON COLUMN "public"."store"."value" IS '值（JSON 格式）';

CREATE INDEX "idx_store_namespace" ON "public"."store" USING gin (namespace);

CREATE INDEX "idx_store_updated_at" ON "public"."store" USING btree (updated_at DESC);

CREATE TABLE "public"."user"(
        "id" varchar(64) NOT NULL,
        "username" varchar(64) NOT NULL,
        "email" varchar(255) NOT NULL,
        "password_hash" varchar(255) NOT NULL,
        "preferences" text,
        "created_at" timestamptz(6) NOT NULL DEFAULT now(),
        "updated_at" timestamptz(6) NOT NULL DEFAULT now(),
        "is_delete" int2 NOT NULL DEFAULT 0,
        CONSTRAINT "user_pkey" PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_user_email ON public."user" USING btree (email);

CREATE INDEX "idx_user_username" ON public."user" USING btree (username);

CREATE INDEX "idx_user_not_deleted" ON public."user" USING btree (id) WHERE (is_delete = 0);
