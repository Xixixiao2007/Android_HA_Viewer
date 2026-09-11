package com.haf1.entitylist;

import java.util.TimeZone;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * 桌面端单测：验证 HaClient 里手写的 ISO8601 解析/生成。
 *
 * 为什么要测：Android 6 的 SimpleDateFormat 不支持 X/XXX 时区模式，
 * 所以这两个方法是手写的。时间解析错了会静默显示错误时间或直接没数据，
 * 而设备上又不好调试，因此在桌面 JDK 上用 javax.xml.datatype（标准实现）当参照。
 */
public class TzTest {

    private static int fail = 0;
    private static int pass = 0;

    private static void eq(String label, Object got, Object want) {
        boolean ok = (got == null) ? (want == null) : got.equals(want);
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        System.out.println((ok ? "  OK   " : "  FAIL ") + label
                + "\n         got  = " + got + "\n         want = " + want);
    }

    /** 构造一个服务端风格的帧（FIN=1、不掩码），专用于测试解码路径。 */
    private static byte[] serverFrame(byte[] payload) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write(0x80 | WsFrame.OP_TEXT);
        int len = payload.length;
        if (len < 126) {
            b.write(len);
        } else if (len <= 0xFFFF) {
            b.write(126);
            b.write((len >>> 8) & 0xFF);
            b.write(len & 0xFF);
        } else {
            b.write(127);
            long l = len;
            for (int i = 7; i >= 0; i--) {
                b.write((int) ((l >>> (8 * i)) & 0xFF));
            }
        }
        b.write(payload, 0, len);
        return b.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        DatatypeFactory df = DatatypeFactory.newInstance();

        // ---------- 1) 解析 HA 实际会返回的各种格式 ----------
        String[] samples = {
                "2026-09-05T14:23:05.123456+08:00",
                "2026-09-05T14:23:05+08:00",
                "2026-09-05T06:23:05.123456+00:00",
                "2026-09-05T06:23:05Z",
                "2026-09-05T06:23:05.123456Z",
                "2026-09-05T01:23:05-05:00",
                "2026-01-01T00:00:00+00:00",
                "2026-12-31T23:59:59+08:00",
                "2026-09-05T06:23:05.5Z",
                "2026-09-05T06:23:05.987654Z",
                "2026-09-05T06:23:05.05Z",
        };
        System.out.println("== parseIso：与 javax.xml.datatype 参照实现逐个比对 ==");
        for (int i = 0; i < samples.length; i++) {
            String s = samples[i];
            long want = df.newXMLGregorianCalendar(s).toGregorianCalendar().getTimeInMillis();
            long got = HaClient.parseIso(s);
            eq(s, Long.valueOf(got), Long.valueOf(want));
        }

        // ---------- 1b) 同一毫秒内的多次变化必须都保留 ----------
        // 这是单测抓出来的真 bug：原来丢掉小数秒 + 用毫秒做去重键，
        // 同一毫秒内的两次变化会互相覆盖，静默丢数据。
        System.out.println();
        System.out.println("== 同一毫秒内的多次变化不会互相覆盖 ==");
        String ra = "2026-09-05T14:23:05.123456+08:00";
        String rb = "2026-09-05T14:23:05.123999+08:00";
        Change ca = new Change(HaClient.parseIso(ra), "A", ra);
        Change cb = new Change(HaClient.parseIso(rb), "B", rb);
        eq("两者毫秒时间戳相同（会撞键）", Boolean.valueOf(ca.time == cb.time), Boolean.TRUE);
        eq("但 raw 键不同（不会丢记录）", Boolean.valueOf(ca.raw.equals(cb.raw)),
                Boolean.FALSE);
        eq("小数秒被保留（.123456 -> 123ms）", Long.valueOf(ca.time),
                Long.valueOf(HaClient.parseIso("2026-09-05T14:23:05.123+08:00")));

        // ---------- 2) 本地时区生成 + 往返一致性 ----------
        System.out.println();
        System.out.println("== isoLocal / 往返一致性 ==");
        long t = df.newXMLGregorianCalendar("2026-09-05T14:23:05+08:00")
                .toGregorianCalendar().getTimeInMillis();

        TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"));
        eq("isoLocal @GMT+08:00", HaClient.isoLocal(t), "2026-09-05T14:23:05+08:00");
        eq("round-trip @GMT+08:00", Long.valueOf(HaClient.parseIso(HaClient.isoLocal(t))),
                Long.valueOf(t));

        TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"));
        eq("isoLocal @GMT-05:00", HaClient.isoLocal(t), "2026-09-05T01:23:05-05:00");
        eq("round-trip @GMT-05:00", Long.valueOf(HaClient.parseIso(HaClient.isoLocal(t))),
                Long.valueOf(t));

        TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"));
        eq("isoLocal @GMT+05:30（半小时偏移）", HaClient.isoLocal(t),
                "2026-09-05T11:53:05+05:30");
        eq("round-trip @GMT+05:30", Long.valueOf(HaClient.parseIso(HaClient.isoLocal(t))),
                Long.valueOf(t));

        // ---------- 3) 健壮性：坏输入不能抛异常 ----------
        System.out.println();
        System.out.println("== 健壮性（坏输入应返回 0，不抛异常）==");
        String[] bad = {"", "not-a-date", "2026-09-05", null};
        for (int i = 0; i < bad.length; i++) {
            long got;
            try {
                got = HaClient.parseIso(bad[i]);
            } catch (Exception e) {
                got = -999;
            }
            eq("parseIso(" + bad[i] + ")", Long.valueOf(got), Long.valueOf(0L));
        }

        // ---------- 4) 筛选逻辑 ----------
        System.out.println();
        System.out.println("== Prefs.accept 黑白名单 ==");
        Prefs p = new Prefs();
        p.mode = Prefs.MODE_WHITELIST;
        eq("白名单为空 -> 全显示", Boolean.valueOf(p.accept("任意内容")), Boolean.TRUE);

        p.keywords.add("报警");
        eq("白名单命中", Boolean.valueOf(p.accept("门磁报警触发")), Boolean.TRUE);
        eq("白名单未命中", Boolean.valueOf(p.accept("温度 25.3")), Boolean.FALSE);
        eq("白名单大小写不敏感", Boolean.valueOf(p.accept("ALARM 报警")), Boolean.TRUE);
        p.mode = Prefs.MODE_BLACKLIST;
        eq("黑名单命中 -> 排除", Boolean.valueOf(p.accept("门磁报警触发")), Boolean.FALSE);
        eq("黑名单未命中 -> 保留", Boolean.valueOf(p.accept("温度 25.3")), Boolean.TRUE);

        // ---------- 5) 刷新间隔越界钳制 ----------
        // 间隔直接决定延迟，填 0 会让轮询退化成死循环打满 CPU/网络，必须夹住。
        System.out.println();
        System.out.println("== Prefs.clampPoll 刷新间隔钳制 ==");
        eq("0 -> 下限", Integer.valueOf(Prefs.clampPoll(0)), Integer.valueOf(Prefs.POLL_MIN));
        eq("负数 -> 下限", Integer.valueOf(Prefs.clampPoll(-5)), Integer.valueOf(Prefs.POLL_MIN));
        eq("1 -> 保持", Integer.valueOf(Prefs.clampPoll(1)), Integer.valueOf(1));
        eq("7 -> 保持", Integer.valueOf(Prefs.clampPoll(7)), Integer.valueOf(7));
        eq("9999 -> 上限", Integer.valueOf(Prefs.clampPoll(9999)), Integer.valueOf(Prefs.POLL_MAX));

        System.out.println();
        // ---------- 6) WebSocket 帧编解码 ----------
        System.out.println();
        System.out.println("== WsFrame 帧编解码（RFC 6455）==");

        byte[] mask = new byte[] {0x37, (byte) 0xfa, 0x21, 0x3d};
        String msg = "{\"type\":\"auth\"}";
        byte[] raw = msg.getBytes("UTF-8");
        byte[] frame = WsFrame.encode(WsFrame.OP_TEXT, raw, mask);
        eq("客户端帧 FIN=1", Boolean.valueOf((frame[0] & 0x80) != 0), Boolean.TRUE);
        eq("客户端帧 opcode=TEXT", Integer.valueOf(frame[0] & 0x0F),
                Integer.valueOf(WsFrame.OP_TEXT));
        eq("客户端帧必须带 MASK 位", Boolean.valueOf((frame[1] & 0x80) != 0), Boolean.TRUE);
        eq("短负载用 7 位长度", Integer.valueOf(frame[1] & 0x7F), Integer.valueOf(raw.length));
        byte[] body = new byte[frame.length - 6];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (frame[6 + i] ^ mask[i & 3]);
        }
        eq("按掩码还原后与原文一致", new String(body, "UTF-8"), msg);

        // 长度落在 7 位 / 16 位 / 64 位三档的边界
        int[] sizes = {0, 5, 125, 126, 1000, 65535, 65536, 70000};
        for (int i = 0; i < sizes.length; i++) {
            byte[] payload = new byte[sizes[i]];
            for (int k = 0; k < payload.length; k++) {
                payload[k] = (byte) (k & 0xFF);
            }
            WsFrame f = WsFrame.read(new java.io.ByteArrayInputStream(serverFrame(payload)));
            eq("长度 " + sizes[i] + " 解析出的字节数",
                    Integer.valueOf(f.payload.length), Integer.valueOf(sizes[i]));
            boolean same = f.payload.length == payload.length && f.fin
                    && f.opcode == WsFrame.OP_TEXT;
            for (int k = 0; same && k < payload.length; k++) {
                if (f.payload[k] != payload[k]) {
                    same = false;
                }
            }
            eq("长度 " + sizes[i] + " 内容与标志位一致", Boolean.valueOf(same), Boolean.TRUE);
        }

        // 协议上服务端不该发掩码帧，但客户端要能容错
        WsFrame mf = WsFrame.read(new java.io.ByteArrayInputStream(
                WsFrame.encode(WsFrame.OP_TEXT, "hello".getBytes("UTF-8"), mask)));
        eq("带掩码帧也能解出原文", new String(mf.payload, "UTF-8"), "hello");

        // 截断的帧必须抛异常，而不是静默返回半截数据
        boolean threw = false;
        try {
            WsFrame.read(new java.io.ByteArrayInputStream(
                    new byte[] {(byte) 0x81, 0x05, (byte) 0x61, (byte) 0x62}));
        } catch (Exception e) {
            threw = true;
        }
        eq("截断帧 -> 抛异常", Boolean.valueOf(threw), Boolean.TRUE);

        System.out.println("==================================================");
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("==================================================");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
