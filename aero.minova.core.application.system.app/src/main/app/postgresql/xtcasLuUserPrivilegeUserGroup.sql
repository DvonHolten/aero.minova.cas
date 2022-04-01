CREATE TABLE IF NOT EXISTS public.xtcasluuserprivilegeusergroup
(
    keylong integer NOT NULL DEFAULT nextval('xtcasluuserprivilegeusergroup_keylong_seq'::regclass),
    userprivilegekey integer NOT NULL,
    usergroupkey integer NOT NULL,
    rowlevelsecurity integer NOT NULL DEFAULT 0,
    lastuser character varying(50) COLLATE pg_catalog."default" NOT NULL,
    lastdate timestamp without time zone NOT NULL DEFAULT now(),
    lastaction integer NOT NULL DEFAULT 1,
    CONSTRAINT xtcasluuserprivilegeusergroup_pkey PRIMARY KEY (keylong),
    CONSTRAINT xtcasluuserprivilegeusergroup_usergroupkey_fkey FOREIGN KEY (usergroupkey)
        REFERENCES public.xtcasusergroup (keylong) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE NO ACTION,
    CONSTRAINT xtcasluuserprivilegeusergroup_userprivilegekey_fkey FOREIGN KEY (userprivilegekey)
        REFERENCES public.xtcasuserprivilege (keylong) MATCH SIMPLE
        ON UPDATE NO ACTION
        ON DELETE NO ACTION
)