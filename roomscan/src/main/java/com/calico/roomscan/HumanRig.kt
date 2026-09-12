package com.calico.roomscan

/** MediaPipe pose landmark indices, the ones this rig actually reads. */
object Lm {
    const val EAR_L = 7
    const val EAR_R = 8
    const val SHOULDER_L = 11
    const val SHOULDER_R = 12
    const val ELBOW_L = 13
    const val ELBOW_R = 14
    const val WRIST_L = 15
    const val WRIST_R = 16
    const val INDEX_L = 19
    const val INDEX_R = 20
    const val HIP_L = 23
    const val HIP_R = 24
    const val KNEE_L = 25
    const val KNEE_R = 26
    const val ANKLE_L = 27
    const val ANKLE_R = 28
    const val FOOT_L = 31
    const val FOOT_R = 32

    const val COUNT = 33
}

/**
 * One bone of the humanoid rig, described by what it should point at rather than by
 * any one model's axis conventions.
 *
 * [nodeNames] and [tipNames] are alternatives, tried in order, so the same rig
 * definition drives a Blender metarig and a Khronos sample model without changes.
 * The bone is rotated so that the direction from itself to its tip follows the
 * direction from landmark group [from] to landmark group [to]. A group of two, such as
 * the pair of hips, is averaged, which is how the torso follows the body midline.
 */
class Bone(
    val nodeNames: List<String>,
    val tipNames: List<String>,
    val from: IntArray,
    val to: IntArray,
)

/**
 * The humanoid skeleton the pose clips drive, in MediaPipe terms.
 *
 * Hips are special: a limb only needs a direction, but the pelvis carries the whole
 * body's orientation, so it is solved from a full basis instead. That is what lets a
 * pushup clip lay the avatar face down while a squat clip keeps it upright.
 *
 * Clavicles, head and toes are deliberately absent. MediaPipe gives no reliable
 * landmark for them, so they keep the model's rest pose and stay out of the way.
 */
object HumanRig {

    val HIPS = listOf("DEF-hips", "hips", "Skeleton_torso_joint_1", "Hips", "mixamorig:Hips")

    /** Node used as the top of the torso when building the pelvis basis. */
    val NECK = listOf("DEF-neck", "neck", "Skeleton_neck_joint_1", "Neck", "mixamorig:Neck")

    private val THIGH_L = listOf("DEF-thigh.L", "thigh_L", "leg_joint_L_1", "LeftUpLeg", "mixamorig:LeftUpLeg")
    private val THIGH_R = listOf("DEF-thigh.R", "thigh_R", "leg_joint_R_1", "RightUpLeg", "mixamorig:RightUpLeg")

    private val MID_HIP = intArrayOf(Lm.HIP_L, Lm.HIP_R)
    private val MID_SHOULDER = intArrayOf(Lm.SHOULDER_L, Lm.SHOULDER_R)
    private val MID_EAR = intArrayOf(Lm.EAR_L, Lm.EAR_R)

    private val SPINE = listOf("DEF-spine.001", "spine", "Skeleton_torso_joint_2", "Spine")
    private val CHEST = listOf("DEF-spine.002", "chest", "torso_joint_3", "Spine1", "Spine2")
    private val FOREARM_L = listOf("DEF-forearm.L", "forearm_L", "Skeleton_arm_joint_L__3_", "LeftForeArm")
    private val FOREARM_R = listOf("DEF-forearm.R", "forearm_R", "Skeleton_arm_joint_R__2_", "RightForeArm")
    private val HAND_L = listOf("DEF-hand.L", "hand_L", "Skeleton_arm_joint_L__2_", "LeftHand")
    private val HAND_R = listOf("DEF-hand.R", "hand_R", "Skeleton_arm_joint_R__3_", "RightHand")
    private val SHIN_L = listOf("DEF-shin.L", "shin_L", "leg_joint_L_2", "LeftLeg")
    private val SHIN_R = listOf("DEF-shin.R", "shin_R", "leg_joint_R_2", "RightLeg")
    private val FOOT_L = listOf("DEF-foot.L", "foot_L", "leg_joint_L_3", "LeftFoot")
    private val FOOT_R = listOf("DEF-foot.R", "foot_R", "leg_joint_R_3", "RightFoot")

    val bones = listOf(
        Bone(SPINE, CHEST, MID_HIP, MID_SHOULDER),
        Bone(CHEST, NECK, MID_HIP, MID_SHOULDER),
        Bone(NECK, listOf("DEF-head", "head", "Skeleton_neck_joint_2", "Head"), MID_SHOULDER, MID_EAR),

        Bone(listOf("DEF-upper_arm.L", "upper_arm_L", "Skeleton_arm_joint_L__4_", "LeftArm"), FOREARM_L,
            intArrayOf(Lm.SHOULDER_L), intArrayOf(Lm.ELBOW_L)),
        Bone(FOREARM_L, HAND_L, intArrayOf(Lm.ELBOW_L), intArrayOf(Lm.WRIST_L)),
        Bone(HAND_L, listOf("DEF-f_middle.01.L", "palm_02_L", "f_middle_01_L"), intArrayOf(Lm.WRIST_L), intArrayOf(Lm.INDEX_L)),

        Bone(listOf("DEF-upper_arm.R", "upper_arm_R", "Skeleton_arm_joint_R", "RightArm"), FOREARM_R,
            intArrayOf(Lm.SHOULDER_R), intArrayOf(Lm.ELBOW_R)),
        Bone(FOREARM_R, HAND_R, intArrayOf(Lm.ELBOW_R), intArrayOf(Lm.WRIST_R)),
        Bone(HAND_R, listOf("DEF-f_middle.01.R", "palm_02_R", "f_middle_01_R"), intArrayOf(Lm.WRIST_R), intArrayOf(Lm.INDEX_R)),

        Bone(THIGH_L, SHIN_L, intArrayOf(Lm.HIP_L), intArrayOf(Lm.KNEE_L)),
        Bone(SHIN_L, FOOT_L, intArrayOf(Lm.KNEE_L), intArrayOf(Lm.ANKLE_L)),
        Bone(FOOT_L, listOf("DEF-toe.L", "toe_L", "leg_joint_L_5", "LeftToeBase"),
            intArrayOf(Lm.ANKLE_L), intArrayOf(Lm.FOOT_L)),

        Bone(THIGH_R, SHIN_R, intArrayOf(Lm.HIP_R), intArrayOf(Lm.KNEE_R)),
        Bone(SHIN_R, FOOT_R, intArrayOf(Lm.KNEE_R), intArrayOf(Lm.ANKLE_R)),
        Bone(FOOT_R, listOf("DEF-toe.R", "toe_R", "leg_joint_R_5", "RightToeBase"),
            intArrayOf(Lm.ANKLE_R), intArrayOf(Lm.FOOT_R)),
    )

    /** Nodes standing in for the left and right hip when measuring the pelvis basis. */
    val hipLeftNodes = THIGH_L
    val hipRightNodes = THIGH_R

    /** First of [names] that exists in [model], or -1. */
    fun resolve(model: GltfModel, names: List<String>): Int {
        for (name in names) {
            val i = model.findNode(name)
            if (i >= 0) return i
        }
        return -1
    }
}
