package teammates.logic.core;

import java.util.List;

import teammates.common.datatransfer.attributes.AccountAttributes;
import teammates.common.datatransfer.attributes.CourseAttributes;
import teammates.common.datatransfer.attributes.InstructorAttributes;
import teammates.common.datatransfer.attributes.StudentAttributes;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.exception.TeammatesException;
import teammates.common.util.Assumption;
import teammates.common.util.Logger;
import teammates.storage.api.AccountsDb;

/**
 * Handles operations related to accounts.
 *
 * @see AccountAttributes
 * @see AccountsDb
 */
public final class AccountsLogic {

    private static final Logger log = Logger.getLogger();

    private static AccountsLogic instance = new AccountsLogic();

    private static final AccountsDb accountsDb = new AccountsDb();

    private static final ProfilesLogic profilesLogic = ProfilesLogic.inst();
    private static final CoursesLogic coursesLogic = CoursesLogic.inst();
    private static final InstructorsLogic instructorsLogic = InstructorsLogic.inst();
    private static final StudentsLogic studentsLogic = StudentsLogic.inst();

    private AccountsLogic() {
        // prevent initialization
    }

    public static AccountsLogic inst() {
        return instance;
    }

    public void createAccount(AccountAttributes accountData)
                    throws InvalidParametersException {

        List<String> invalidityInfo = accountData.getInvalidityInfo();
        if (!invalidityInfo.isEmpty()) {
            throw new InvalidParametersException(invalidityInfo);
        }

        log.info("going to create account :\n" + accountData.toString());

        accountsDb.createAccount(accountData);
    }

    public AccountAttributes getAccount(String googleId) {
        return accountsDb.getAccount(googleId);
    }

    public boolean isAccountPresent(String googleId) {
        return accountsDb.getAccount(googleId) != null;
    }

    public boolean isAccountAnInstructor(String googleId) {
        AccountAttributes a = accountsDb.getAccount(googleId);
        return a != null && a.isInstructor;
    }

    public List<AccountAttributes> getInstructorAccounts() {
        return accountsDb.getInstructorAccounts();
    }

    public String getCourseInstitute(String courseId) {
        CourseAttributes cd = coursesLogic.getCourse(courseId);
        Assumption.assertNotNull("Trying to getCourseInstitute for inexistent course with id " + courseId, cd);
        List<InstructorAttributes> instructorList = instructorsLogic.getInstructorsForCourse(cd.getId());

        Assumption.assertTrue("Course has no instructors: " + cd.getId(), !instructorList.isEmpty());
        // Retrieve institute field from one of the instructors of the course
        String institute = "";
        for (InstructorAttributes instructor : instructorList) {
            String instructorGoogleId = instructor.googleId;
            if (instructorGoogleId == null) {
                continue;
            }
            AccountAttributes instructorAcc = accountsDb.getAccount(instructorGoogleId);
            if (instructorAcc != null) {
                institute = instructorAcc.institute;
                break;
            }
        }
        Assumption.assertNotEmpty("No institute found for the course", institute);
        return institute;
    }

    public void updateAccount(AccountAttributes account)
            throws InvalidParametersException, EntityDoesNotExistException {
        accountsDb.updateAccount(account);
    }

    /**
     * Joins the user as a student.
     */
    public StudentAttributes joinCourseForStudent(String registrationKey, String googleId)
            throws InvalidParametersException, EntityDoesNotExistException, EntityAlreadyExistsException {
        StudentAttributes student = validateStudentJoinRequest(registrationKey, googleId);

        // Register the student
        student.googleId = googleId;
        try {
            studentsLogic.updateStudentCascade(student.email, student);
        } catch (EntityDoesNotExistException e) {
            Assumption.fail("Student disappeared while trying to register " + TeammatesException.toStringWithStackTrace(e));
        }

        if (accountsDb.getAccount(googleId) == null) {
            createStudentAccount(student);
        }

        return student;
    }

    /**
     * Joins the user as an instructor and sets the institute if it is not null.
     * If the given instructor is null, the instructor is given the institute of an existing instructor of the same course.
     */
    public InstructorAttributes joinCourseForInstructor(String encryptedKey, String googleId, String institute)
            throws InvalidParametersException, EntityDoesNotExistException, EntityAlreadyExistsException {
        InstructorAttributes instructor = validateInstructorJoinRequest(encryptedKey, googleId);

        // Register the instructor
        instructor.googleId = googleId;
        try {
            instructorsLogic.updateInstructorByEmail(instructor.email, instructor);
        } catch (EntityDoesNotExistException e) {
            Assumption.fail("Instructor disappeared while trying to register "
                    + TeammatesException.toStringWithStackTrace(e));
        }

        AccountAttributes account = accountsDb.getAccount(googleId);
        String instituteToSave = institute == null ? getCourseInstitute(instructor.courseId) : institute;

        if (account == null) {
            createAccount(AccountAttributes.builder()
                    .withGoogleId(googleId)
                    .withName(instructor.name)
                    .withEmail(instructor.email)
                    .withInstitute(instituteToSave)
                    .withIsInstructor(true)
                    .build());
        } else {
            makeAccountInstructor(googleId);
        }

        // Update the googleId of the student entity for the instructor which was created from sample data.
        StudentAttributes student = studentsLogic.getStudentForEmail(instructor.courseId, instructor.email);
        if (student != null) {
            student.googleId = googleId;
            try {
                studentsLogic.updateStudentCascade(instructor.email, student);
            } catch (EntityDoesNotExistException e) {
                Assumption.fail("Student disappeared while updating " + TeammatesException.toStringWithStackTrace(e));
            }
        }

        return instructor;
    }

    private InstructorAttributes validateInstructorJoinRequest(String encryptedKey, String googleId)
            throws EntityDoesNotExistException, EntityAlreadyExistsException {

        InstructorAttributes instructorForKey = instructorsLogic.getInstructorForRegistrationKey(encryptedKey);

        if (instructorForKey == null) {
            throw new EntityDoesNotExistException("No instructor with given registration key: " + encryptedKey);
        }

        if (instructorForKey.isRegistered()) {
            if (instructorForKey.googleId.equals(googleId)) {
                AccountAttributes existingAccount = accountsDb.getAccount(googleId);
                if (existingAccount != null && existingAccount.isInstructor) {
                    throw new EntityAlreadyExistsException("Instructor has already joined course");
                }
            } else {
                throw new EntityAlreadyExistsException("Instructor has already joined course");
            }
        } else {
            // Check if this Google ID has already joined this course
            InstructorAttributes existingInstructor =
                    instructorsLogic.getInstructorForGoogleId(instructorForKey.courseId, googleId);

            if (existingInstructor != null) {
                throw new EntityAlreadyExistsException("Instructor has already joined course");
            }
        }

        return instructorForKey;
    }

    private StudentAttributes validateStudentJoinRequest(String encryptedKey, String googleId)
            throws EntityDoesNotExistException, EntityAlreadyExistsException {

        StudentAttributes studentRole = studentsLogic.getStudentForRegistrationKey(encryptedKey);

        if (studentRole == null) {
            throw new EntityDoesNotExistException("No student with given registration key: " + encryptedKey);
        }

        if (studentRole.isRegistered()) {
            throw new EntityAlreadyExistsException("Student has already joined course");
        }

        // Check if this Google ID has already joined this course
        StudentAttributes existingStudent =
                studentsLogic.getStudentForCourseIdAndGoogleId(studentRole.course, googleId);

        if (existingStudent != null) {
            throw new EntityAlreadyExistsException("Student has already joined course");
        }

        return studentRole;
    }

    public void downgradeInstructorToStudentCascade(String googleId) {
        instructorsLogic.deleteInstructorsForGoogleIdAndCascade(googleId);
        makeAccountNonInstructor(googleId);
    }

    public void makeAccountNonInstructor(String googleId) {
        AccountAttributes account = accountsDb.getAccount(googleId);
        if (account == null) {
            log.warning("Accounts logic trying to modify non-existent account a non-instructor :" + googleId);
        } else {
            account.isInstructor = false;
            try {
                accountsDb.updateAccount(account);
            } catch (InvalidParametersException | EntityDoesNotExistException e) {
                Assumption.fail("Invalid account data detected unexpectedly "
                                + "while removing instruction privileges from account :" + account.toString());
            }
        }
    }

    public void makeAccountInstructor(String googleId) {

        AccountAttributes account = accountsDb.getAccount(googleId);

        if (account == null) {
            log.warning("Accounts logic trying to modify non-existent account an instructor:" + googleId);
        } else {
            account.isInstructor = true;
            try {
                accountsDb.updateAccount(account);
            } catch (InvalidParametersException | EntityDoesNotExistException e) {
                Assumption.fail("Invalid account data detected unexpectedly "
                                + "while adding instruction privileges to account :" + account.toString());
            }
        }
    }

    /**
     * Deletes both instructor and student privileges, as long as the account and associated student profile.
     *
     * <ul>
     * <li>Does not delete courses, which can result in orphan courses.</li>
     * <li>Fails silently if no such account.</li>
     * </ul>
     */
    public void deleteAccountCascade(String googleId) {
        profilesLogic.deleteStudentProfile(googleId);
        instructorsLogic.deleteInstructorsForGoogleIdAndCascade(googleId);
        studentsLogic.deleteStudentsForGoogleIdAndCascade(googleId);
        accountsDb.deleteAccount(googleId);
        //TODO: deal with orphan courses, submissions etc.
    }

    private void createStudentAccount(StudentAttributes student)
            throws InvalidParametersException {

        AccountAttributes account = AccountAttributes.builder()
                .withGoogleId(student.googleId)
                .withEmail(student.email)
                .withName(student.name)
                .withIsInstructor(false)
                .withInstitute(getCourseInstitute(student.course))
                .build();

        accountsDb.createAccount(account);
    }

}
