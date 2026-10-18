package com.bobincraft.movement;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Быстрый клиентский A* поиск пути по воксельной сетке мира Minecraft.
 * Поддерживает подъем на 1 блок вверх (прыжок), спуск на 1-3 блока вниз,
 * а также передвижение в воде (плавание и выход из воды на берег).
 */
public class PathFinder {

    public static class Node implements Comparable<Node> {
        public final BlockPos pos;
        public final Node parent;
        public final double gCost;
        public final double hCost;
        public final double fCost;

        public Node(BlockPos pos, Node parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.fCost, o.fCost);
        }
    }

    public static List<Vec3d> findPath(ClientWorld world, BlockPos start, BlockPos goal, int maxNodes) {
        BlockPos actualGoal = findStandablePosNear(world, goal);
        BlockPos actualStart = findStandablePosNear(world, start);

        if (actualGoal == null || actualStart == null) {
            return Collections.singletonList(new Vec3d(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5));
        }

        if (actualStart.equals(actualGoal)) {
            return Collections.singletonList(new Vec3d(actualGoal.getX() + 0.5, actualGoal.getY(), actualGoal.getZ() + 0.5));
        }

        // Если цель в соседнем блоке, строим прямой переход
        if (actualStart.isWithinDistance(actualGoal, 1.8)) {
            List<Vec3d> direct = new ArrayList<>();
            direct.add(new Vec3d(actualStart.getX() + 0.5, actualStart.getY(), actualStart.getZ() + 0.5));
            direct.add(new Vec3d(actualGoal.getX() + 0.5, actualGoal.getY(), actualGoal.getZ() + 0.5));
            return direct;
        }

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Map<BlockPos, Double> openCosts = new HashMap<>();
        Set<BlockPos> closedSet = new HashSet<>();

        Node startNode = new Node(actualStart, null, 0.0, heuristic(actualStart, actualGoal));
        openSet.add(startNode);
        openCosts.put(actualStart, 0.0);

        Node closestNode = startNode;
        double minDistanceToGoal = heuristic(actualStart, actualGoal);

        int nodesSearched = 0;
        int[] dX = {1, -1, 0, 0, 1, 1, -1, -1};
        int[] dZ = {0, 0, 1, -1, 1, -1, 1, -1};

        while (!openSet.isEmpty() && nodesSearched < maxNodes) {
            Node current = openSet.poll();
            nodesSearched++;

            if (current != startNode && (current.pos.equals(actualGoal) || current.pos.isWithinDistance(actualGoal, 1.2))) {
                return reconstructPath(current);
            }

            closedSet.add(current.pos);

            double h = heuristic(current.pos, actualGoal);
            if (h < minDistanceToGoal) {
                minDistanceToGoal = h;
                closestNode = current;
            }

            for (int i = 0; i < 8; i++) {
                int nx = current.pos.getX() + dX[i];
                int nz = current.pos.getZ() + dZ[i];
                boolean diagonal = (i >= 4);

                BlockPos neighborPos = findValidWalkableY(world, nx, current.pos.getY(), nz);
                if (neighborPos == null || closedSet.contains(neighborPos)) {
                    continue;
                }

                if (diagonal) {
                    BlockPos p1 = findValidWalkableY(world, current.pos.getX() + dX[i], current.pos.getY(), current.pos.getZ());
                    BlockPos p2 = findValidWalkableY(world, current.pos.getX(), current.pos.getY(), current.pos.getZ() + dZ[i]);
                    if (p1 == null || p2 == null) continue;
                    // Диагональ рядом с дверью/калиткой задевает косяк и персонаж застревает — идём только прямо
                    if (isDoorLike(world, current.pos) || isDoorLike(world, neighborPos)
                            || isDoorLike(world, p1) || isDoorLike(world, p2)) continue;
                }

                double stepCost = diagonal ? 1.414 : 1.0;
                stepCost += Math.abs(neighborPos.getY() - current.pos.getY()) * 0.5;

                if (isWater(world, neighborPos)) {
                    stepCost += 0.3;
                }

                double newG = current.gCost + stepCost;

                if (!openCosts.containsKey(neighborPos) || newG < openCosts.get(neighborPos)) {
                    openCosts.put(neighborPos, newG);
                    Node neighborNode = new Node(neighborPos, current, newG, heuristic(neighborPos, actualGoal));
                    openSet.add(neighborNode);
                }
            }
        }

        return reconstructPath(closestNode);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.sqrt(a.getSquaredDistance(b));
    }

    public static boolean isWater(ClientWorld world, BlockPos pos) {
        return world.getFluidState(pos).isIn(FluidTags.WATER);
    }

    public static boolean isPassable(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || isWater(world, pos) || state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        // Ковры, тонкие плиты и снег: персонаж свободно по ним ходит (интерьер зданий)
        if (state.getCollisionShape(world, pos).getMax(net.minecraft.util.math.Direction.Axis.Y) <= 0.13) {
            return true;
        }
        net.minecraft.block.Block b = state.getBlock();
        // Двери, люки и калитки — это проходы, через которые персонаж может проходить!
        if (b instanceof net.minecraft.block.DoorBlock) {
            return !state.isOf(net.minecraft.block.Blocks.IRON_DOOR);
        }
        if (b instanceof net.minecraft.block.FenceGateBlock || b instanceof net.minecraft.block.TrapdoorBlock) {
            return !state.isOf(net.minecraft.block.Blocks.IRON_TRAPDOOR);
        }
        return false;
    }

    /** Дверь или калитка в этой клетке (на уровне ног или головы). */
    public static boolean isDoorLike(ClientWorld world, BlockPos feet) {
        return isDoorBlock(world.getBlockState(feet)) || isDoorBlock(world.getBlockState(feet.up()));
    }

    public static boolean isDoorBlock(BlockState state) {
        net.minecraft.block.Block b = state.getBlock();
        return b instanceof net.minecraft.block.DoorBlock || b instanceof net.minecraft.block.FenceGateBlock;
    }

    public static boolean isSolidFloor(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
    }

    public static boolean isWalkableOrSwimmable(ClientWorld world, BlockPos feet) {
        // Поддержка плавания в воде
        if (isWater(world, feet) && isPassable(world, feet.up())) {
            return true;
        }
        // Поддержка ходьбы по твердой поверхности
        return isPassable(world, feet) && isPassable(world, feet.up()) && isSolidFloor(world, feet.down());
    }

    private static BlockPos findValidWalkableY(ClientWorld world, int x, int startY, int z) {
        for (int dy = 1; dy >= -3; dy--) {
            BlockPos feet = new BlockPos(x, startY + dy, z);
            if (isWalkableOrSwimmable(world, feet)) {
                return feet;
            }
        }
        return null;
    }

    private static BlockPos findStandablePosNear(ClientWorld world, BlockPos pos) {
        if (isWalkableOrSwimmable(world, pos)) {
            return pos;
        }
        if (isWalkableOrSwimmable(world, pos.up())) {
            return pos.up();
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos p = pos.add(dx, dy, dz);
                    if (isWalkableOrSwimmable(world, p)) {
                        return p;
                    }
                }
            }
        }
        return pos;
    }

    private static List<Vec3d> reconstructPath(Node endNode) {
        List<Vec3d> path = new ArrayList<>();
        Node cur = endNode;
        while (cur != null) {
            path.add(new Vec3d(cur.pos.getX() + 0.5, cur.pos.getY(), cur.pos.getZ() + 0.5));
            cur = cur.parent;
        }
        Collections.reverse(path);
        return path;
    }
}
