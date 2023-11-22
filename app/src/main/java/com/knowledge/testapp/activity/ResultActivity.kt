package com.knowledge.testapp.activity

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.knowledge.testapp.QuizValues
import com.knowledge.testapp.localdb.UserPathDbManager
import com.knowledge.testapp.data.GameMode
import com.knowledge.testapp.data.User
import com.knowledge.testapp.data.PathRecord
import com.knowledge.testapp.databinding.ActivityLoseBinding
import com.knowledge.testapp.databinding.ActivityResultBinding
import com.knowledge.testapp.utils.ModifyingStrings.Companion.createTableName
import com.knowledge.testapp.utils.ModifyingStrings.Companion.sanitizeEmail
import com.knowledge.testapp.utils.NavigationUtils
import com.knowledge.testapp.R

class ResultActivity : AppCompatActivity() {

    private lateinit var bindingResult: ActivityResultBinding
    private lateinit var bindingLose: ActivityLoseBinding

    private lateinit var scoreView: TextView
    private lateinit var pathView: TextView

    private var dbUserPathDbManager: UserPathDbManager? = null
    lateinit var startConcept:String
    lateinit var goalConcept:String
    var pathLength: Int = 0
    private var totalSteps = 0
    var pathList: MutableList<String> = ArrayList()
    var win:Boolean = false
    var worldRecord = true
    var brandNewPath = true
    var diff = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pathList = intent.getStringArrayListExtra(QuizValues.PATH)!!
        pathLength = pathList.size ?: 0
        val pathText = pathList.joinToString("->")

        win = intent.getBooleanExtra(QuizValues.WIN, false)
        totalSteps = intent.getIntExtra(QuizValues.TOTAL_STEPS, 0)

        if (win) {
            bindingResult = ActivityResultBinding.inflate(layoutInflater)
            setContentView(bindingResult.root)
            scoreView = bindingResult.tvScore
            pathView = bindingResult.tvPath
        } else {
            bindingLose = ActivityLoseBinding.inflate(layoutInflater)
            setContentView(bindingLose.root)
            scoreView = bindingLose.tvScore
            pathView = bindingLose.tvPath
        }

        startConcept = intent.getStringExtra(QuizValues.STARTING_CONCEPT).toString()
        goalConcept = intent.getStringExtra(QuizValues.GOAL_CONCEPT).toString()

        pathView.text = pathText
        processTheResult()
        saveToLocalDB()
    }

    fun tryAgain(view: View) {
        NavigationUtils.tryAgain(this)
    }

    fun goToMainMenu(view: View) {
        NavigationUtils.goToMainMenu(this)
    }

    fun processTheResult() {

        val userEmail = FirebaseAuth.getInstance().currentUser?.email
        if (userEmail != null) {
            // Call the function to fetch the user's data and handle the result in the callback
            getUserDataByEmail(userEmail) { user ->
                if (user != null) {
                    // Example: Display the user's recordsHeld and currentScore
                    println("Records Held: ${user.recordsHeld}")
                    println("Current Score: ${user.currentScore}")

                    if(win) {
                        saveWorldRecordToRemoteDB(
                            user,
                            startConcept, goalConcept, pathList, totalSteps
                        )
                    } else {
                        scoreView.text = "You haven't found $goalConcept"
                    }

                } else {
                    // User's data doesn't exist in the database (not registered?)
                    println("User data not found.")
                }
            }
        }
    }

    fun getUserDataByEmail(userEmail: String, onDataLoaded: (User?) -> Unit) {
        val database: FirebaseDatabase = FirebaseDatabase.getInstance()
        val usersRef: DatabaseReference = database.getReference("users")

        // Construct the DatabaseReference using the user's email
        val userRecordsRef = usersRef.child(sanitizeEmail(userEmail))

        // Use a ValueEventListener to fetch the user's data from the "users" table
        userRecordsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                // Check if the user's data exists in the database
                if (dataSnapshot.exists()) {
                    // Convert the dataSnapshot to a User object
                    val user = dataSnapshot.getValue(User::class.java)

                    // Now you have the User object containing the user's data
                    // Call the callback function and pass the User object
                    onDataLoaded(user)
                } else {
                    // User's data doesn't exist in the database (not registered?)
                    // Call the callback function with null
                    onDataLoaded(null)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Handle any errors that occurred while reading data
                // Call the callback function with null
                onDataLoaded(null)
            }
        })
    }

    fun saveWorldRecordToRemoteDB(
        user: User, // User object containing user email (or ID)
        startingConcept: String,
        goalConcept: String,
        path: List<String>,
        totalSteps: Int
    ) {

        val database: FirebaseDatabase = FirebaseDatabase.getInstance()

        val refStringWorldsRecords: String
        val refStringUsers: String

        when (QuizValues.gameMode) {
            GameMode.FIND_YOUR_LIKINGS -> {
                refStringWorldsRecords = createTableName(QuizValues.worldRecords_FIND_YOUR_LIKINGS, user.languageCode)
                refStringUsers = createTableName(QuizValues.topUsers_FIND_YOUR_LIKINGS, user.languageCode)
            }
            GameMode.LIKING_SPECTRUM_JOURNEY -> {
                refStringWorldsRecords = createTableName(QuizValues.worldRecords_LIKING_SPECTRUM_JOURNEY, user.languageCode)
                refStringUsers = createTableName(QuizValues.topUsers_LIKING_SPECTRUM_JOURNEY, user.languageCode)
            }
            GameMode.ANYFIN_CAN_HAPPEN -> {
                refStringWorldsRecords = createTableName(QuizValues.worldRecords_ANYFIN_CAN_HAPPEN, user.languageCode)
                refStringUsers = createTableName(QuizValues.topUsers_ANYFIN_CAN_HAPPEN, user.languageCode)
            }
        }

        val usersRef: DatabaseReference = database.getReference(refStringUsers)

        // Check if there is any existing record with the same startingConcept and goalConcept and win=true
        //val query = ref.orderByChild("startingConcept").equalTo(startingConcept)
        //val query = ref.orderByKey().startAt("\uf8ff" + "${startingConcept}_${goalConcept}").endAt("${startingConcept}_${goalConcept}")
        val startingGoalConcept = "${startingConcept}_$goalConcept"
        val query = usersRef.orderByChild("startingGoalConcept").equalTo(startingGoalConcept)

        val context = this

        // Use a single listener to combine the results of both queries
        val listener = object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                    val existingRecord = dataSnapshot.children.firstOrNull()

                    if (existingRecord != null) {
                        brandNewPath = false
                        // The record already exists
                        val existingSteps = existingRecord.child("steps").getValue(Int::class.java)
                        diff = totalSteps - existingSteps!!
                        if (existingSteps <= totalSteps) {
                            worldRecord = false
                            val textNotEnoughForWR = getString(R.string.not_enough_for_wr)
                            Toast.makeText(context, "$diff $textNotEnoughForWR", Toast.LENGTH_SHORT).show()
                        } else {
                            val recordRef = existingRecord.ref
                            val recordId = existingRecord.key
                            val userSanitizedEmail = recordId!!.substringBefore("_") // Extract user email from the world record ID

                            // Update the user's records and score as a record is removed
                            val userRecordsRef = usersRef.child(userSanitizedEmail)
                            userRecordsRef.runTransaction(object : Transaction.Handler {
                                override fun doTransaction(currentData: MutableData): Transaction.Result {
                                    val currentUser = currentData.getValue(User::class.java)

                                    if (currentUser != null) {
                                        // Update the user's records and current score
                                        val newRecordsHeld = currentUser.recordsHeld - 1
                                        val newCurrentScore = currentUser.currentScore - 100

                                        currentData.child("recordsHeld").value = newRecordsHeld
                                        currentData.child("currentScore").value = newCurrentScore
                                    }

                                    return Transaction.success(currentData)
                                }

                                override fun onComplete(
                                    error: DatabaseError?,
                                    committed: Boolean,
                                    currentData: DataSnapshot?
                                ) {
                                    // Handle transaction completion
                                    if (error != null) {
                                        println("Transaction failed: ${error.message}")
                                    } else {
                                        println("Transaction completed successfully.")
                                    }
                                }
                            })

                            // Remove the world record
                            recordRef.removeValue()
                        }
                    }

                // If there is no existing record or the new path length is greater, save the data
                if (worldRecord) {
                    // Generate a unique ID for the record with the user's sanitized email
                    val recordId = "${sanitizeEmail(user.email)}_${startingConcept}_${goalConcept}"

                    val recordPath = "$refStringWorldsRecords/$recordId"

                    val recordData = mapOf(
                        "startingConcept" to startingConcept,
                        "goalConcept" to goalConcept,
                        "path" to path,
                        "steps" to totalSteps,
                        "win" to true,
                        "startingGoalConcept" to startingGoalConcept
                    )

                    FirebaseDatabase.getInstance().getReference(recordPath)
                        .setValue(recordData)
                        .addOnSuccessListener {
                            // Data successfully written to the "worldRecords" node
                            val textEnoughForWR = getString(R.string.enough_for_wr)
                            Toast.makeText(context, textEnoughForWR, Toast.LENGTH_SHORT).show()

                            // Update user's records and current score
                            val userRecordsRef = usersRef.child(sanitizeEmail(user.email))
                            userRecordsRef.runTransaction(object : Transaction.Handler {
                                override fun doTransaction(currentData: MutableData): Transaction.Result {
                                    val currentUser = currentData.getValue(User::class.java)

                                    if (currentUser == null) {
                                        // User doesn't exist in the database, create a new entry
                                        val newUser = user.copy(recordsHeld = 1, currentScore = 100)
                                        currentData.setValue(newUser)
                                    } else {
                                        // Update the user's records and current score
                                        val newRecordsHeld = currentUser.recordsHeld + 1
                                        val newCurrentScore = currentUser.currentScore + 100

                                        currentData.child("recordsHeld").setValue(newRecordsHeld)
                                        currentData.child("currentScore").setValue(newCurrentScore)
                                    }

                                    return Transaction.success(currentData)
                                }

                                override fun onComplete(
                                    error: DatabaseError?,
                                    committed: Boolean,
                                    currentData: DataSnapshot?
                                ) {
                                    // Handle transaction completion
                                    if (error != null) {
                                        println("Transaction failed: ${error.message}")
                                    } else {
                                        println("Transaction completed successfully.")
                                        updateUI(worldRecord, diff, totalSteps)
                                    }
                                }
                            })
                        }
                        .addOnFailureListener { error ->
                            println("Error saving data: $error")
                        }
                } else {
                    updateUI(worldRecord, diff, totalSteps)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                println("Error reading data: $error")
            }
        }

        // Add the listener to the query
        query.addListenerForSingleValueEvent(listener)


    }

    private fun updateUI(worldRecord: Boolean, diff: Int, totalSteps: Int) {
        val formattedString = getString(R.string.found_concept_steps)
        val resultInformationText = formattedString.format(goalConcept, totalSteps)
        scoreView.text = resultInformationText
        val addText = if (worldRecord) {
            if(brandNewPath) {
                val textWRomantic = getString(R.string.new_world_record_romantic)
                textWRomantic
            } else {
                val newWorldRecordWithDiff = getString(R.string.new_world_record_with_diff, -diff)
                newWorldRecordWithDiff
            }

        } else {
            val textNotEnoughForWR = getString(R.string.not_enough_for_wr)
            "$diff $textNotEnoughForWR"
        }

        scoreView.text = "${scoreView.text}\n$addText"
    }


    fun saveToLocalDB()
    {
        dbUserPathDbManager = UserPathDbManager(this)
        dbUserPathDbManager!!.open()
        dbUserPathDbManager!!.beginTransaction()
        val itemUser = PathRecord()
        itemUser.startingConcept = startConcept
        itemUser.goalConcept = goalConcept
        itemUser.path = pathList as ArrayList<String>
        itemUser.steps = pathLength
        itemUser.win = win
        dbUserPathDbManager!!.insert(QuizValues.USER!!.email,itemUser)
        dbUserPathDbManager!!.setTransactionSuccess()
        dbUserPathDbManager!!.endTransaction()
        dbUserPathDbManager!!.close()
    }
}