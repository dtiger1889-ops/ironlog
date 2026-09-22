package dev.ironlog.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private object Routes {
    // Bottom-tab roots
    const val PROFILE = "profile"
    const val HISTORY_ROOT = "history_root"
    const val WORKOUT = "workout"
    const val EXERCISES = "exercises"
    const val MEASURE = "measure"

    // Full-screen routes (suppress bottom bar)
    const val ACTIVE = "active"
    const val SETTINGS = "settings"
    const val GYM = "gym"
    const val FINDER = "finder"

    // Sub-routes pushed on top of tabs
    const val DETAIL = "detail/{workoutId}"
    const val TEMPLATE_EDITOR = "templateEditor/{templateId}"
    const val RECONCILE = "reconcile"
    const val EXERCISE_DETAIL = "exerciseDetail/{exerciseId}"

    fun detail(workoutId: Long) = "detail/$workoutId"
    fun templateEditor(templateId: Long) = "templateEditor/$templateId"
    fun exerciseDetail(exerciseId: Long) = "exerciseDetail/$exerciseId"
}

private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Profile(Routes.PROFILE, "Profile", Icons.Filled.AccountCircle),
    History(Routes.HISTORY_ROOT, "History", Icons.Filled.DateRange),
    Workout(Routes.WORKOUT, "Workout", Icons.Filled.Home),
    Exercises(Routes.EXERCISES, "Exercises", Icons.Filled.List),
    Measure(Routes.MEASURE, "Measure", Icons.Filled.Star),
}

private val FULL_SCREEN_ROUTES = setOf(Routes.ACTIVE, Routes.SETTINGS, Routes.GYM, Routes.FINDER)

@Composable
fun IronlogApp(vm: IronlogViewModel) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute !in FULL_SCREEN_ROUTES

    Scaffold(
        // F4: default contentWindowInsets (safeDrawing) reserves the BOTTOM system-bar inset
        // as phantom padding on the NavHost whenever bottomBar renders nothing -- which is
        // exactly the case for every full-screen route (ACTIVE/SETTINGS/GYM/FINDER). Each of
        // those screens already owns its own bottom inset (ActiveWorkoutScreen's RestBar calls
        // navigationBarsPadding() itself; the others have their own Scaffold+TopAppBar), so the
        // leftover pass-through stacked a SECOND copy of the nav-bar height below the visible
        // RestBar -- the "excess whitespace" bug. Keep only the TOP inset here: HomeScreen (the
        // one tab screen with no TopAppBar of its own) still needs it to clear the status bar,
        // and the tab NavigationBar below sizes itself from its own internal inset handling,
        // not from this value.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Top),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Routes.WORKOUT,
            modifier = Modifier.padding(innerPadding),
        ) {
            // ---------- Tab: Profile ----------
            composable(Routes.PROFILE) {
                ProfileScreen(
                    vm = vm,
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }

            // ---------- Tab: History ----------
            composable(Routes.HISTORY_ROOT) {
                HistoryScreen(
                    vm = vm,
                    onOpenWorkout = { id -> nav.navigate(Routes.detail(id)) },
                    onBack = null,
                )
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("workoutId") { type = NavType.LongType }),
            ) { entry ->
                val workoutId = entry.arguments?.getLong("workoutId") ?: 0L
                WorkoutDetailScreen(
                    vm = vm,
                    workoutId = workoutId,
                    onBack = { nav.popBackStack() },
                    onStartEdit = {
                        vm.startEditingWorkout(workoutId) {
                            nav.navigate(Routes.ACTIVE)
                        }
                    },
                )
            }

            // ---------- Tab: Workout (home + active) ----------
            composable(Routes.WORKOUT) {
                HomeScreen(
                    vm = vm,
                    onStartWorkout = {
                        vm.startEmpty(System.currentTimeMillis())
                        nav.navigate(Routes.ACTIVE)
                    },
                    onStartTemplate = { templateId ->
                        vm.startFromTemplate(templateId, System.currentTimeMillis()) {
                            nav.navigate(Routes.ACTIVE)
                        }
                    },
                    onEditTemplate = { templateId ->
                        nav.navigate(Routes.templateEditor(templateId))
                    },
                    onNewTemplate = { nav.navigate(Routes.templateEditor(0L)) },
                    onOpenHistory = { nav.navigate(Routes.HISTORY_ROOT) },
                    onResume = { nav.navigate(Routes.ACTIVE) },
                )
            }
            composable(Routes.ACTIVE) {
                ActiveWorkoutScreen(
                    vm = vm,
                    // Guarded: finish/discard can trigger both an explicit onLeave and the
                    // draft==null LaunchedEffect — a second pop would empty the NavHost (blank
                    // screen). Only pop while ACTIVE is actually the current destination.
                    onLeave = {
                        if (nav.currentBackStackEntry?.destination?.route == Routes.ACTIVE) {
                            nav.popBackStack()
                        }
                    },
                    onOpenExerciseDetail = { id -> nav.navigate(Routes.exerciseDetail(id)) },
                )
            }
            composable(
                route = Routes.TEMPLATE_EDITOR,
                arguments = listOf(navArgument("templateId") { type = NavType.LongType }),
            ) { entry ->
                TemplateEditorScreen(
                    vm = vm,
                    templateId = entry.arguments?.getLong("templateId") ?: 0L,
                    onDone = { nav.popBackStack() },
                )
            }

            // ---------- Tab: Exercises ----------
            composable(Routes.EXERCISES) {
                ExercisesScreen(
                    vm = vm,
                    onOpenReconcile = { nav.navigate(Routes.RECONCILE) },
                    onOpenDetail = { exerciseId -> nav.navigate(Routes.exerciseDetail(exerciseId)) },
                    onOpenGym = { nav.navigate(Routes.GYM) },
                )
            }
            composable(Routes.RECONCILE) {
                ReconcileScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                route = Routes.EXERCISE_DETAIL,
                arguments = listOf(navArgument("exerciseId") { type = NavType.LongType }),
            ) { entry ->
                ExerciseDetailScreen(
                    vm = vm,
                    exerciseId = entry.arguments?.getLong("exerciseId") ?: 0L,
                    onBack = { nav.popBackStack() },
                )
            }

            // ---------- Tab: Measure (M6) ----------
            composable(Routes.MEASURE) {
                MeasureScreen(
                    vm = vm,
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }

            // ---------- Full-screen: Settings ----------
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                )
            }

            // ---------- Full-screen: My Gym (M5) ----------
            composable(Routes.GYM) {
                GymScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenFinder = { nav.navigate(Routes.FINDER) },
                )
            }
            composable(Routes.FINDER) {
                FinderScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                    onOpenDetail = { exerciseId -> nav.navigate(Routes.exerciseDetail(exerciseId)) },
                )
            }
        }
    }
}
