{
    "uniqueId": "spam-blocker-lite-9uBCmCxM",
    "category": "Spam Blocker Lite",
    "description": "The number of IP addresses sending spam.",
    "displayOrder": 202,
    "enabled": true,
    "javaClass": "com.untangle.node.reporting.ReportEntry",
    "orderByColumn": "value",
    "orderDesc": true,
    "units": "msgs",
    "pieGroupColumn": "s_client_addr",
    "pieSumColumn": "count(*)",
    "conditions": [
        {
            "column": "spam_blocker_lite_is_spam",
            "javaClass": "com.untangle.node.reporting.SqlCondition",
            "operator": "=",
            "value": "true"
        }
    ],
    "readOnly": true,
    "table": "mail_addrs",
    "title": "Top Spam Sender Hosts",
    "type": "PIE_GRAPH"
}
