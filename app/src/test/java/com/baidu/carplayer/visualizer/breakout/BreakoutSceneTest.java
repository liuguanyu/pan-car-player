package com.baidu.carplayer.visualizer.breakout;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BreakoutSceneTest {
    @Test public void ballNeverFallsBelowPaddleUnderRandomAudio() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        BreakoutScene.Audio a = new BreakoutScene.Audio();
        Random random = new Random(7);
        for (int i = 0; i < 20000; i++) {
            a.bass = random.nextFloat();
            a.mid = random.nextFloat();
            a.treble = random.nextFloat();
            a.volume = random.nextFloat();
            a.centroid = random.nextFloat();
            a.flux = random.nextFloat();
            a.beatPosition = i * .02;
            a.beatConfidence = .8f;
            a.periodMs = 480f;
            a.peak = random.nextFloat() > .97f;
            scene.update(1f / 60f, a);
            for (BreakoutScene.Ball b : scene.balls()) {
                if (!b.active) continue;
                assertTrue("ball escaped bottom: " + b.y,
                        b.y <= scene.aspect() - BreakoutScene.BALL_R + 1e-4f);
                assertTrue("ball escaped top: " + b.y, b.y >= -1e-4f);
            }
        }
    }

    @Test public void predictUnfoldsSideWallReflections() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        BreakoutScene.Ball b = scene.balls()[0];
        b.x = .2f; b.y = .3f; b.vx = .2f; b.vy = .4f;
        float top = .6f - BreakoutScene.PADDLE_MARGIN - BreakoutScene.BALL_R;
        float t = (top - b.y) / b.vy;
        assertEquals(.2f + .2f * t, scene.predictLanding(b), 1e-4f);

        b.vx = 1.6f;
        float u = .2f + 1.6f * t;
        float expected = u - 2f * (float) Math.floor(u / 2f);
        expected = expected <= 1f ? expected : 2f - expected;
        assertEquals(expected, scene.predictLanding(b), 1e-4f);
    }

    @Test public void formationsUseDifferentMasks() {
        int grid = count(BreakoutScene.F_GRID);
        int diamond = count(BreakoutScene.F_DIAMOND);
        int arch = count(BreakoutScene.F_ARCH);
        int wave = count(BreakoutScene.F_WAVE);
        assertEquals(BreakoutScene.BRICK_COUNT, grid);
        assertEquals(BreakoutScene.BRICK_COUNT, count(BreakoutScene.F_RING));
        assertEquals(BreakoutScene.BRICK_COUNT, count(BreakoutScene.F_SPECTRUM));
        assertTrue(diamond > 0 && diamond < grid);
        assertTrue(arch > 0 && arch < grid);
        assertTrue(wave > 0 && wave < grid);
    }

    @Test public void splitNeverExceedsBallCap() {
        BreakoutScene scene = new BreakoutScene();
        BreakoutScene.Audio a = new BreakoutScene.Audio();
        a.treble = .9f; a.bass = .5f; a.volume = .5f;
        a.beatConfidence = .9f; a.periodMs = 500f;
        for (int i = 0; i < 1200; i++) {
            a.peak = i % 60 == 0;
            a.beatPosition = i * .03;
            scene.update(1f / 60f, a);
            int active = 0;
            for (BreakoutScene.Ball b : scene.balls()) if (b.active) active++;
            assertTrue(active <= BreakoutScene.MAX_BALLS);
        }
    }

    @Test public void bassHeavyBeatProducesChopWithLowerSpeed() {
        BreakoutScene scene = tuned(.95f, .05f, .2f, .9f);
        BreakoutScene.Ball b = placeAtPaddle(scene, 0f);
        scene.update(1f / 60f, tunedAudio(.95f, .05f, .2f, .9f));
        assertEquals(BreakoutScene.SHOT_CHOP, scene.paddle().shotType);
        assertTrue("chop should slow the ball: " + b.speedScale, b.speedScale < .95f);
        assertTrue(b.spin == 0f);
    }

    @Test public void trebleHeavyAudioProducesSpinWithSideRotation() {
        BreakoutScene scene = tuned(.05f, .95f, .85f, .9f);
        BreakoutScene.Ball b = placeAtPaddle(scene, .6f);
        scene.update(1f / 60f, tunedAudio(.05f, .95f, .85f, .9f));
        assertEquals(BreakoutScene.SHOT_SPIN, scene.paddle().shotType);
        assertTrue("spin should be applied: " + b.spin, Math.abs(b.spin) > .01f);

        // 侧旋在飞行中造成横向偏移，并随时间衰减。
        b.spin = .1f; b.vx = 0f; b.x = .5f;
        float x0 = b.x;
        BreakoutScene.Audio idle = new BreakoutScene.Audio();
        idle.beatConfidence = 0f;
        for (int i = 0; i < 10; i++) scene.update(1f / 60f, idle);
        assertTrue("spin should curve trajectory: " + b.x, b.x > x0 + .005f);
        assertTrue("spin should decay: " + b.spin, b.spin < .1f);
    }

    @Test public void bottomEscapeTriggersSaveShot() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        BreakoutScene.Ball b = scene.balls()[0];
        b.x = .2f; b.y = scene.aspect() - BreakoutScene.BALL_R * .5f;
        b.vx = 0f; b.vy = .3f;
        scene.update(1f / 60f, new BreakoutScene.Audio());
        assertEquals(BreakoutScene.SHOT_SAVE, scene.paddle().shotType);
        assertTrue(scene.rescueFlash() > .5f);
        assertTrue(b.vy < 0f);
        assertTrue(b.y <= scene.aspect() - BreakoutScene.BALL_R + 1e-4f);
    }

    @Test public void spinShotAimsBackToBricksWhenBallOnOppositeSide() {
        // 先预热 EMA，让频谱质心升到触发搓球的阈值。
        BreakoutScene scene = tuned(.05f, .95f, .85f, .9f);
        // 截图局势：砖块全在左侧，球在右侧接近挡板，高频音频应触发搓球。
        for (BreakoutScene.Brick brick : scene.bricks()) brick.alive = brick.col <= 2;
        BreakoutScene.Ball b = scene.balls()[0];
        b.active = true;
        b.x = .82f;
        b.y = scene.aspect() - BreakoutScene.PADDLE_MARGIN - BreakoutScene.BALL_R * .4f;
        b.vx = 0f; b.vy = .3f;
        scene.paddle().x = .82f;

        BreakoutScene.Audio a = tunedAudio(.05f, .95f, .85f, .9f);
        scene.update(1f / 60f, a);
        assertEquals(BreakoutScene.SHOT_SPIN, scene.paddle().shotType);
        assertTrue("spin shot should send ball left toward bricks: " + b.vx, b.vx < 0f);
        assertTrue("spin should curve left too: " + b.spin, b.spin < 0f);
    }

    @Test public void aimPrefersItemBricksOverPlainBricks() {
        BreakoutScene scene = tuned(.05f, .95f, .85f, .9f);
        // 左右砖数相同，但左侧是道具砖（威力更大）。
        for (BreakoutScene.Brick brick : scene.bricks()) {
            brick.alive = brick.col <= 2 || brick.col >= 7;
            brick.type = brick.col <= 2 ? BreakoutScene.ITEM_FAST : BreakoutScene.ITEM_NONE;
        }
        BreakoutScene.Ball b = scene.balls()[0];
        b.active = true;
        b.x = .55f;
        b.y = scene.aspect() - BreakoutScene.PADDLE_MARGIN - BreakoutScene.BALL_R * .4f;
        b.vx = 0f; b.vy = .3f;
        scene.paddle().x = .55f;
        scene.update(1f / 60f, tunedAudio(.05f, .95f, .85f, .9f));
        assertEquals(BreakoutScene.SHOT_SPIN, scene.paddle().shotType);
        assertTrue("should aim at item bricks: " + b.vx, b.vx < 0f);
    }

    @Test public void aimPrefersDenserSide() {
        BreakoutScene scene = tuned(.05f, .95f, .85f, .9f);
        // 左侧 4 列普通砖，右侧 2 列普通砖。
        for (BreakoutScene.Brick brick : scene.bricks()) {
            brick.alive = brick.col <= 3 || brick.col >= 8;
            brick.type = BreakoutScene.ITEM_NONE;
        }
        BreakoutScene.Ball b = scene.balls()[0];
        b.active = true;
        b.x = .55f;
        b.y = scene.aspect() - BreakoutScene.PADDLE_MARGIN - BreakoutScene.BALL_R * .4f;
        b.vx = 0f; b.vy = .3f;
        scene.paddle().x = .55f;
        scene.update(1f / 60f, tunedAudio(.05f, .95f, .85f, .9f));
        assertEquals(BreakoutScene.SHOT_SPIN, scene.paddle().shotType);
        assertTrue("should aim at denser side: " + b.vx, b.vx < 0f);
    }

    @Test public void saveShotAimsTowardBricksAndWidensPaddle() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        // 残局：只剩最左一列砖，球在右侧底部即将漏掉。
        for (BreakoutScene.Brick brick : scene.bricks()) brick.alive = brick.col == 0;
        BreakoutScene.Ball b = scene.balls()[0];
        b.x = .8f; b.y = scene.aspect() - BreakoutScene.BALL_R * .05f;
        b.vx = 0f; b.vy = .3f;
        scene.paddle().x = .8f;
        scene.update(1f / 60f, new BreakoutScene.Audio());
        assertEquals(BreakoutScene.SHOT_SAVE, scene.paddle().shotType);
        assertTrue("save should aim left toward bricks: " + b.vx, b.vx < 0f);
        assertTrue("save should widen paddle physically", scene.wideActive());
    }

    private static BreakoutScene tuned(float bass, float treble,
                                       float centroid, float confidence) {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        for (BreakoutScene.Ball b : scene.balls()) b.active = false;
        BreakoutScene.Audio a = tunedAudio(bass, treble, centroid, confidence);
        for (int i = 0; i < 60; i++) {
            a.beatPosition = i * .05;
            scene.update(1f / 60f, a);
        }
        return scene;
    }

    private static BreakoutScene.Audio tunedAudio(float bass, float treble,
                                                  float centroid, float confidence) {
        BreakoutScene.Audio a = new BreakoutScene.Audio();
        a.bass = bass; a.mid = .3f; a.treble = treble; a.volume = .6f;
        a.centroid = centroid; a.beatConfidence = confidence; a.periodMs = 500f;
        return a;
    }

    private static BreakoutScene.Ball placeAtPaddle(BreakoutScene scene, float offset) {
        BreakoutScene.Ball b = scene.balls()[0];
        b.active = true;
        b.x = scene.paddle().x + scene.paddle().width * .5f * offset;
        b.y = scene.aspect() - BreakoutScene.PADDLE_MARGIN - BreakoutScene.BALL_R * .4f;
        b.vx = 0f; b.vy = .3f; b.speedScale = 1f; b.spin = 0f;
        return b;
    }

    @Test public void strongBeatEnablesPiercing() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        BreakoutScene.Audio a = new BreakoutScene.Audio();
        a.mid = .3f; a.treble = .1f; a.volume = .6f;
        a.beatConfidence = .9f; a.periodMs = 500f;
        for (int i = 0; i < 45; i++) {
            boolean surge = i >= 21;
            a.bass = surge ? .95f : .3f;
            a.flux = surge ? .9f : 0f;
            a.beatPosition = i * .05;
            scene.update(1f / 60f, a);
        }
        assertTrue(scene.piercing());
    }

    @Test public void treblePeakEnablesChainBlast() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        BreakoutScene.Audio a = new BreakoutScene.Audio();
        a.treble = .9f; a.bass = .1f; a.volume = .6f; a.periodMs = 500f;
        for (int i = 0; i < 60; i++) {
            a.beatPosition = i * .05;
            scene.update(1f / 60f, a);
        }
        a.peak = true;
        scene.update(1f / 60f, a);
        assertTrue(scene.chaining());
    }

    @Test public void saveShotClearsColumnOfBricks() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        int before = aliveBricks(scene);
        BreakoutScene.Ball b = scene.balls()[0];
        b.x = .2f; b.y = scene.aspect() - BreakoutScene.BALL_R * .5f;
        b.vx = 0f; b.vy = .3f;
        scene.update(1f / 60f, new BreakoutScene.Audio());
        assertTrue("save should clear bricks: " + aliveBricks(scene),
                aliveBricks(scene) < before);
        assertTrue(scene.shockwaveFlash() > .5f);
    }

    @Test public void chopAimsBallTowardRemainingBricks() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        // 残局：只剩最左侧一列砖，球在右侧。
        for (BreakoutScene.Brick brick : scene.bricks()) brick.alive = brick.col == 0;
        BreakoutScene.Ball b = scene.balls()[0];
        b.active = true;
        scene.paddle().x = .8f;
        b.x = .8f;
        b.y = scene.aspect() - BreakoutScene.PADDLE_MARGIN - BreakoutScene.BALL_R * .4f;
        b.vx = 0f; b.vy = .3f;

        // 音频偏高频，排除音乐削球分支，只验证残局瞄准。
        BreakoutScene.Audio a = tunedAudio(.1f, .9f, .2f, .9f);
        a.bass = .1f; a.treble = .9f;
        scene.update(1f / 60f, a);
        assertEquals(BreakoutScene.SHOT_CHOP, scene.paddle().shotType);
        assertTrue("chop should aim left toward bricks: " + b.vx, b.vx < 0f);
    }

    @Test public void threatBallPrefersSoonestArrival() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        BreakoutScene.Ball near = scene.balls()[0];
        BreakoutScene.Ball far = scene.balls()[1];
        near.active = far.active = true;
        // near 更快到达挡板线
        near.y = scene.aspect() - .2f; near.vy = .3f;
        far.y = scene.aspect() - .5f; far.vy = .3f;
        assertTrue(scene.threatBall() == near);

        far.vy = 1f;
        assertTrue(scene.threatBall() == far);
    }

    @Test public void paddleRepositionsTowardBricksWhileBallRises() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        // 只留左侧砖，球在右侧且正在上升。
        for (BreakoutScene.Brick brick : scene.bricks()) brick.alive = brick.col == 0;
        BreakoutScene.Ball b = scene.balls()[0];
        b.x = .85f; b.y = scene.aspect() - .10f;
        b.vx = 0f; b.vy = -.3f;
        scene.paddle().x = .85f;
        BreakoutScene.Audio a = new BreakoutScene.Audio();
        a.beatConfidence = 0f;
        for (int i = 0; i < 30; i++) scene.update(1f / 60f, a);
        assertTrue("paddle should drift toward bricks: " + scene.paddle().x,
                scene.paddle().x < .84f);
    }

    @Test public void smartSteeringNeverDropsBall() {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        BreakoutScene.Audio a = new BreakoutScene.Audio();
        a.beatConfidence = 0f;
        for (int i = 0; i < 6000; i++) {
            a.beatPosition = i * .02;
            scene.update(1f / 60f, a);
            for (BreakoutScene.Ball b : scene.balls()) {
                if (!b.active) continue;
                assertTrue("ball escaped: " + b.y,
                        b.y <= scene.aspect() - BreakoutScene.BALL_R + 1e-4f);
            }
        }
    }

    @Test public void itemBricksApplyTheirEffects() {
        assertTrue(hitItem(BreakoutScene.ITEM_SLOW).slowActive());
        assertTrue(hitItem(BreakoutScene.ITEM_FAST).fastActive());
        assertTrue(hitItem(BreakoutScene.ITEM_WIDE).wideActive());
        assertTrue(hitItem(BreakoutScene.ITEM_NARROW).narrowActive());
        BreakoutScene multi = hitItem(BreakoutScene.ITEM_MULTI);
        int active = 0;
        for (BreakoutScene.Ball b : multi.balls()) if (b.active) active++;
        assertTrue("multi ball should split: " + active, active >= 3);
    }

    private static BreakoutScene hitItem(int type) {
        BreakoutScene scene = new BreakoutScene();
        scene.setAspect(.6f);
        for (BreakoutScene.Brick brick : scene.bricks()) {
            if (brick.type != type) continue;
            BreakoutScene.Ball b = scene.balls()[0];
            b.active = true;
            b.x = brick.x; b.y = brick.y;
            b.vx = 0f; b.vy = -.4f; b.spin = 0f; b.speedScale = 1f;
            BreakoutScene.Audio a = new BreakoutScene.Audio();
            a.beatConfidence = 0f;
            scene.update(1f / 60f, a);
            return scene;
        }
        throw new AssertionError("missing item brick type " + type);
    }

    private static int aliveBricks(BreakoutScene scene) {
        int n = 0;
        for (BreakoutScene.Brick b : scene.bricks()) if (b.alive) n++;
        return n;
    }

    private static int count(int formation) {
        int n = 0;
        for (int row = 0; row < BreakoutScene.ROWS; row++) {
            for (int col = 0; col < BreakoutScene.COLS; col++) {
                if (BreakoutScene.formationMask(formation, col, row)) n++;
            }
        }
        return n;
    }

    /** 任何阵型下，砖块都不应互相叠压（环阵内圈曾因砖宽固定而重叠）。 */
    @Test public void bricksDoNotOverlapInAnyFormation() {
        for (int f = 0; f < BreakoutScene.FORMATIONS; f++) {
            BreakoutScene scene = new BreakoutScene();
            scene.setAspect(348f / 1280f);   // 车机可视区 1280x348
            scene.reset();
            scene.forceFormation(f);
            BreakoutScene.Brick[] list = scene.bricks();
            for (int i = 0; i < list.length; i++) {
                if (!list[i].alive) continue;
                for (int j = i + 1; j < list.length; j++) {
                    if (!list[j].alive) continue;
                    float dx = Math.abs(list[i].x - list[j].x);
                    float dy = Math.abs(list[i].y - list[j].y);
                    float needX = (list[i].w + list[j].w) * .5f;
                    float needY = (list[i].h + list[j].h) * .5f;
                    assertTrue("formation " + f + " overlap: ["
                                    + list[i].x + "," + list[i].y + " w" + list[i].w + " h" + list[i].h
                                    + "] vs [" + list[j].x + "," + list[j].y + " w" + list[j].w
                                    + " h" + list[j].h + "]",
                            dx >= needX * .96f || dy >= needY * .96f);
                }
            }
        }
    }
}
