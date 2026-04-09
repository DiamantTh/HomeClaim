package systems.diath.homeclaim.api.license

/**
 * SPDX License Identifier with OSS compliance scoring.
 * 
 * Based on SPDX License List: https://spdx.org/licenses/
 * OSS Definition: https://opensource.org/osd
 */
data class SpdxLicense(
    val id: String,
    val name: String,
    val isOsiApproved: Boolean,
    val isFsfLibre: Boolean,
    val category: LicenseCategory,
    val permissions: Set<LicensePermission>,
    val conditions: Set<LicenseCondition>,
    val limitations: Set<LicenseLimitation>,
    val ossScore: Double,
    val copyleftStrength: CopyleftStrength,
    val spdxUrl: String
)

enum class LicenseCategory {
    PERMISSIVE,           // MIT, BSD, Apache-2.0
    WEAK_COPYLEFT,        // LGPL, MPL
    STRONG_COPYLEFT,      // GPL, AGPL
    PROPRIETARY,          // Closed source
    PUBLIC_DOMAIN         // CC0, Unlicense
}

enum class CopyleftStrength {
    NONE,        // 0 - Permissive (MIT, BSD, Apache)
    WEAK,        // 1 - File-level copyleft (MPL)
    MODERATE,    // 2 - Library-level copyleft (LGPL)
    STRONG,      // 3 - Project-level copyleft (GPL-2.0, GPL-3.0)
    NETWORK      // 4 - Network copyleft (AGPL-3.0)
}

enum class LicensePermission {
    COMMERCIAL_USE,
    MODIFICATION,
    DISTRIBUTION,
    PRIVATE_USE,
    PATENT_USE
}

enum class LicenseCondition {
    LICENSE_AND_COPYRIGHT_NOTICE,
    STATE_CHANGES,
    DISCLOSE_SOURCE,
    SAME_LICENSE,
    SAME_LICENSE_FILE,
    SAME_LICENSE_LIBRARY
}

enum class LicenseLimitation {
    LIABILITY,
    WARRANTY,
    TRADEMARK_USE,
    PATENT_USE_LIMITED
}

/**
 * SPDX License Database with OSS Scoring.
 * 
 * Scoring Criteria (0-100):
 * - OSI Approved: +40
 * - FSF Free/Libre: +20
 * - Commercial Use: +15
 * - Patent Grant: +10
 * - Modification Rights: +10
 * - No Copyleft: +5
 * - Weak Copyleft: +3
 * - Strong Copyleft: -5
 */
object SpdxLicenseRegistry {
    
    private val licenses = mapOf(
        // ==========================================
        // PERMISSIVE LICENSES (High OSS Score)
        // ==========================================
        "MIT" to SpdxLicense(
            id = "MIT",
            name = "MIT License",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.PERMISSIVE,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 100.0,
            copyleftStrength = CopyleftStrength.NONE,
            spdxUrl = "https://spdx.org/licenses/MIT.html"
        ),
        
        "Apache-2.0" to SpdxLicense(
            id = "Apache-2.0",
            name = "Apache License 2.0",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.PERMISSIVE,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE,
                LicensePermission.PATENT_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE,
                LicenseCondition.STATE_CHANGES
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY,
                LicenseLimitation.TRADEMARK_USE
            ),
            ossScore = 100.0,
            copyleftStrength = CopyleftStrength.NONE,
            spdxUrl = "https://spdx.org/licenses/Apache-2.0.html"
        ),
        
        "BSD-3-Clause" to SpdxLicense(
            id = "BSD-3-Clause",
            name = "BSD 3-Clause \"New\" or \"Revised\" License",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.PERMISSIVE,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 95.0,
            copyleftStrength = CopyleftStrength.NONE,
            spdxUrl = "https://spdx.org/licenses/BSD-3-Clause.html"
        ),
        
        "BSD-2-Clause" to SpdxLicense(
            id = "BSD-2-Clause",
            name = "BSD 2-Clause \"Simplified\" License",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.PERMISSIVE,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 95.0,
            copyleftStrength = CopyleftStrength.NONE,
            spdxUrl = "https://spdx.org/licenses/BSD-2-Clause.html"
        ),
        
        "ISC" to SpdxLicense(
            id = "ISC",
            name = "ISC License",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.PERMISSIVE,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 95.0,
            copyleftStrength = CopyleftStrength.NONE,
            spdxUrl = "https://spdx.org/licenses/ISC.html"
        ),
        
        // ==========================================
        // WEAK COPYLEFT LICENSES
        // ==========================================
        "MPL-2.0" to SpdxLicense(
            id = "MPL-2.0",
            name = "Mozilla Public License 2.0",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.WEAK_COPYLEFT,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE,
                LicensePermission.PATENT_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE,
                LicenseCondition.DISCLOSE_SOURCE,
                LicenseCondition.SAME_LICENSE_FILE
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY,
                LicenseLimitation.TRADEMARK_USE
            ),
            ossScore = 83.0,
            copyleftStrength = CopyleftStrength.WEAK,
            spdxUrl = "https://spdx.org/licenses/MPL-2.0.html"
        ),
        
        "LGPL-3.0-only" to SpdxLicense(
            id = "LGPL-3.0-only",
            name = "GNU Lesser General Public License v3.0 only",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.WEAK_COPYLEFT,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE,
                LicensePermission.PATENT_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE,
                LicenseCondition.DISCLOSE_SOURCE,
                LicenseCondition.STATE_CHANGES,
                LicenseCondition.SAME_LICENSE_LIBRARY
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 78.0,
            copyleftStrength = CopyleftStrength.MODERATE,
            spdxUrl = "https://spdx.org/licenses/LGPL-3.0-only.html"
        ),
        
        "LGPL-2.1-only" to SpdxLicense(
            id = "LGPL-2.1-only",
            name = "GNU Lesser General Public License v2.1 only",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.WEAK_COPYLEFT,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE,
                LicenseCondition.DISCLOSE_SOURCE,
                LicenseCondition.STATE_CHANGES,
                LicenseCondition.SAME_LICENSE_LIBRARY
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 73.0,
            copyleftStrength = CopyleftStrength.MODERATE,
            spdxUrl = "https://spdx.org/licenses/LGPL-2.1-only.html"
        ),
        
        // ==========================================
        // STRONG COPYLEFT LICENSES
        // ==========================================
        "GPL-3.0-only" to SpdxLicense(
            id = "GPL-3.0-only",
            name = "GNU General Public License v3.0 only",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.STRONG_COPYLEFT,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE,
                LicensePermission.PATENT_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE,
                LicenseCondition.DISCLOSE_SOURCE,
                LicenseCondition.STATE_CHANGES,
                LicenseCondition.SAME_LICENSE
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 65.0,
            copyleftStrength = CopyleftStrength.STRONG,
            spdxUrl = "https://spdx.org/licenses/GPL-3.0-only.html"
        ),
        
        "GPL-2.0-only" to SpdxLicense(
            id = "GPL-2.0-only",
            name = "GNU General Public License v2.0 only",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.STRONG_COPYLEFT,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE,
                LicenseCondition.DISCLOSE_SOURCE,
                LicenseCondition.STATE_CHANGES,
                LicenseCondition.SAME_LICENSE
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 60.0,
            copyleftStrength = CopyleftStrength.STRONG,
            spdxUrl = "https://spdx.org/licenses/GPL-2.0-only.html"
        ),
        
        "AGPL-3.0-only" to SpdxLicense(
            id = "AGPL-3.0-only",
            name = "GNU Affero General Public License v3.0 only",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.STRONG_COPYLEFT,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE,
                LicensePermission.PATENT_USE
            ),
            conditions = setOf(
                LicenseCondition.LICENSE_AND_COPYRIGHT_NOTICE,
                LicenseCondition.DISCLOSE_SOURCE,
                LicenseCondition.STATE_CHANGES,
                LicenseCondition.SAME_LICENSE
            ),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 65.0,
            copyleftStrength = CopyleftStrength.NETWORK,
            spdxUrl = "https://spdx.org/licenses/AGPL-3.0-only.html"
        ),
        
        // ==========================================
        // PUBLIC DOMAIN
        // ==========================================
        "CC0-1.0" to SpdxLicense(
            id = "CC0-1.0",
            name = "Creative Commons Zero v1.0 Universal",
            isOsiApproved = false,
            isFsfLibre = true,
            category = LicenseCategory.PUBLIC_DOMAIN,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE
            ),
            conditions = emptySet(),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY,
                LicenseLimitation.TRADEMARK_USE,
                LicenseLimitation.PATENT_USE_LIMITED
            ),
            ossScore = 95.0,
            copyleftStrength = CopyleftStrength.NONE,
            spdxUrl = "https://spdx.org/licenses/CC0-1.0.html"
        ),
        
        "Unlicense" to SpdxLicense(
            id = "Unlicense",
            name = "The Unlicense",
            isOsiApproved = true,
            isFsfLibre = true,
            category = LicenseCategory.PUBLIC_DOMAIN,
            permissions = setOf(
                LicensePermission.COMMERCIAL_USE,
                LicensePermission.MODIFICATION,
                LicensePermission.DISTRIBUTION,
                LicensePermission.PRIVATE_USE
            ),
            conditions = emptySet(),
            limitations = setOf(
                LicenseLimitation.LIABILITY,
                LicenseLimitation.WARRANTY
            ),
            ossScore = 95.0,
            copyleftStrength = CopyleftStrength.NONE,
            spdxUrl = "https://spdx.org/licenses/Unlicense.html"
        )
    )
    
    fun getLicense(id: String): SpdxLicense? = licenses[id]
    
    fun searchLicenses(query: String): List<SpdxLicense> {
        val lowerQuery = query.lowercase()
        return licenses.values.filter {
            it.id.lowercase().contains(lowerQuery) ||
            it.name.lowercase().contains(lowerQuery)
        }.sortedByDescending { it.ossScore }
    }
    
    fun getAllLicenses(): List<SpdxLicense> = licenses.values.sortedByDescending { it.ossScore }
    
    fun getByCategory(category: LicenseCategory): List<SpdxLicense> =
        licenses.values.filter { it.category == category }.sortedByDescending { it.ossScore }
    
    fun getOsiApproved(): List<SpdxLicense> =
        licenses.values.filter { it.isOsiApproved }.sortedByDescending { it.ossScore }
}
