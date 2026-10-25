#!/usr/bin/env python3
"""build_gold_test.py

Creates the 114 hand-curated, independent Gold Test Suite (gold_test.jsonl)
strictly following schema 2.0.0 for CASCADE26.
Every positive case includes literal evidence quotes verified against query.
"""

import json
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent.resolve()
TOOLS_FILE = SCRIPT_DIR / "tools.needle.json"

with open(TOOLS_FILE, "r", encoding="utf-8") as f:
    ALL_TOOLS = json.load(f)

GOLD_CASES = []

def add_gold(query, tool_name, args):
    if tool_name is None:
        GOLD_CASES.append({
            "query": query,
            "tools": ALL_TOOLS,
            "answers": []
        })
    else:
        # Verify evidence quotes
        quotes = args.get("evidence_quotes", [])
        for q in quotes:
            if q not in query:
                raise ValueError(f"Evidence quote '{q}' not found in query '{query}'")
        GOLD_CASES.append({
            "query": query,
            "tools": ALL_TOOLS,
            "answers": [
                {
                    "name": tool_name,
                    "arguments": args
                }
            ]
        })

# ==============================================================================
# TOOL 1: submit_emergency_report (20 cases)
# ==============================================================================
add_gold(
    "Urgent! Water is rising fast in our living room at 742 Evergreen Terrace. We are 4 people trapped on the second floor.",
    "submit_emergency_report",
    {
        "needs": ["rescue"],
        "need_details": [],
        "hazards": ["flood"],
        "location_text": "742 Evergreen Terrace",
        "affected_people_count": 4,
        "urgency_cue": "Water is rising fast",
        "evidence_quotes": ["Water is rising fast", "742 Evergreen Terrace", "4 people trapped", "second floor"]
    }
)

add_gold(
    "Grandma has run out of insulin and cannot walk. We are at Pine Ridge Senior Living, Room 104. Please send medication immediately.",
    "submit_emergency_report",
    {
        "needs": ["medicine", "medical_help"],
        "need_details": ["insulin"],
        "hazards": [],
        "location_text": "Pine Ridge Senior Living, Room 104",
        "affected_people_count": 1,
        "urgency_cue": "immediately",
        "evidence_quotes": ["run out of insulin", "Pine Ridge Senior Living, Room 104", "cannot walk", "immediately"]
    }
)

add_gold(
    "Massive structural collapse at 500 Industrial Parkway. Roof gave way, 3 workers pinned under steel girders. Critical emergency!",
    "submit_emergency_report",
    {
        "needs": ["rescue", "medical_help"],
        "need_details": [],
        "hazards": ["structural_collapse"],
        "location_text": "500 Industrial Parkway",
        "affected_people_count": 3,
        "urgency_cue": "Critical emergency!",
        "evidence_quotes": ["structural collapse", "500 Industrial Parkway", "3 workers pinned", "Critical emergency!"]
    }
)

add_gold(
    "There is a severe chemical leak from tanker car behind Crestview High School. Yellow fumes spreading towards football field.",
    "submit_emergency_report",
    {
        "needs": ["evacuation"],
        "need_details": [],
        "hazards": ["chemical_hazard"],
        "location_text": "behind Crestview High School",
        "affected_people_count": None,
        "urgency_cue": "Yellow fumes spreading",
        "evidence_quotes": ["severe chemical leak", "behind Crestview High School", "Yellow fumes spreading"]
    }
)

add_gold(
    "A landslide just smashed through the guardrail on Route 9 near mile marker 14. Road is completely wiped out.",
    "submit_emergency_report",
    {
        "needs": [],
        "need_details": [],
        "hazards": ["landslide", "unsafe_road"],
        "location_text": "Route 9 near mile marker 14",
        "affected_people_count": None,
        "urgency_cue": "landslide just smashed",
        "evidence_quotes": ["landslide just smashed", "Route 9 near mile marker 14", "Road is completely wiped out"]
    }
)

# Number trap: "Route 4" and "at 3 PM" are NOT people count!
add_gold(
    "Tree caught fire on electric wire outside 112 Elm Street along Route 4 around 3 PM. Sparks showering down on wooden porch.",
    "submit_emergency_report",
    {
        "needs": [],
        "need_details": [],
        "hazards": ["fire", "power_outage"],
        "location_text": "112 Elm Street along Route 4",
        "affected_people_count": None,
        "urgency_cue": "Sparks showering down",
        "evidence_quotes": ["Tree caught fire", "112 Elm Street along Route 4", "Sparks showering down"]
    }
)

add_gold(
    "We have 8 elderly patients stranded without drinking water and generator fuel at St. Jude Care Center. We need water and fuel supplies now.",
    "submit_emergency_report",
    {
        "needs": ["water", "supplies"],
        "need_details": ["drinking water", "generator fuel"],
        "hazards": ["power_outage"],
        "location_text": "St. Jude Care Center",
        "affected_people_count": 8,
        "urgency_cue": "now",
        "evidence_quotes": ["8 elderly patients stranded", "St. Jude Care Center", "drinking water", "generator fuel"]
    }
)

add_gold(
    "Flash flood waters reached hood of car on South Creek Road. Driver and 1 passenger stuck inside vehicle, water rushing past doors.",
    "submit_emergency_report",
    {
        "needs": ["rescue"],
        "need_details": [],
        "hazards": ["flood"],
        "location_text": "South Creek Road",
        "affected_people_count": 2,
        "urgency_cue": "water rushing past doors",
        "evidence_quotes": ["Flash flood waters", "South Creek Road", "Driver and 1 passenger stuck inside vehicle", "water rushing past doors"]
    }
)

add_gold(
    "Wildfire jumping ridge top towards Oakwood Subdivision. Wind pushing heavy smoke into valley, 12 residents trapped at dead-end cul-de-sac.",
    "submit_emergency_report",
    {
        "needs": ["evacuation", "rescue"],
        "need_details": [],
        "hazards": ["fire", "severe_weather"],
        "location_text": "Oakwood Subdivision",
        "affected_people_count": 12,
        "urgency_cue": "Wildfire jumping ridge top",
        "evidence_quotes": ["Wildfire jumping ridge top", "Oakwood Subdivision", "12 residents trapped"]
    }
)

add_gold(
    "Need urgent medical help at Beacon Wharf Pier 3! Man had compound fracture from falling rigging, bleeding heavily.",
    "submit_emergency_report",
    {
        "needs": ["medical_help"],
        "need_details": [],
        "hazards": [],
        "location_text": "Beacon Wharf Pier 3",
        "affected_people_count": 1,
        "urgency_cue": "bleeding heavily",
        "evidence_quotes": ["Need urgent medical help", "Beacon Wharf Pier 3", "compound fracture", "bleeding heavily"]
    }
)

add_gold(
    "Cell towers are dead and telephone poles collapsed in North Haven. 5 families have no food or communication since yesterday.",
    "submit_emergency_report",
    {
        "needs": ["food", "communication"],
        "need_details": [],
        "hazards": ["power_outage"],
        "location_text": "North Haven",
        "affected_people_count": None,
        "urgency_cue": None,
        "evidence_quotes": ["North Haven", "no food or communication", "Cell towers are dead"]
    }
)

add_gold(
    "Gas main ruptured and hissing loudly next to Lincoln Middle School gymnasium. Strong odor, risk of explosion.",
    "submit_emergency_report",
    {
        "needs": ["evacuation"],
        "need_details": [],
        "hazards": ["chemical_hazard", "fire"],
        "location_text": "Lincoln Middle School gymnasium",
        "affected_people_count": None,
        "urgency_cue": "hissing loudly",
        "evidence_quotes": ["Gas main ruptured and hissing loudly", "Lincoln Middle School gymnasium", "risk of explosion"]
    }
)

add_gold(
    "Storm surge broke seawall at Bayview Harbor. 2 fishermen clinging to piling in open water. Need boat rescue!",
    "submit_emergency_report",
    {
        "needs": ["rescue"],
        "need_details": ["boat rescue"],
        "hazards": ["flood", "severe_weather"],
        "location_text": "Bayview Harbor",
        "affected_people_count": 2,
        "urgency_cue": "clinging to piling in open water",
        "evidence_quotes": ["Storm surge broke seawall", "Bayview Harbor", "2 fishermen clinging to piling", "Need boat rescue!"]
    }
)

add_gold(
    "Active armed violence reported near City Market on 4th Street. Gunshots heard, crowds fleeing, at least 2 people down on sidewalk.",
    "submit_emergency_report",
    {
        "needs": ["medical_help", "rescue"],
        "need_details": [],
        "hazards": ["violence"],
        "location_text": "City Market on 4th Street",
        "affected_people_count": 2,
        "urgency_cue": "Gunshots heard",
        "evidence_quotes": ["Active armed violence", "City Market on 4th Street", "at least 2 people down on sidewalk"]
    }
)

add_gold(
    "Tornado touchdown just occurred near Mill Creek Trailer Park. Several mobile homes flipped, unknown number of people crying for help inside.",
    "submit_emergency_report",
    {
        "needs": ["rescue", "medical_help"],
        "need_details": [],
        "hazards": ["severe_weather", "structural_collapse"],
        "location_text": "Mill Creek Trailer Park",
        "affected_people_count": None,
        "urgency_cue": "Tornado touchdown just occurred",
        "evidence_quotes": ["Tornado touchdown just occurred", "Mill Creek Trailer Park", "homes flipped", "crying for help"]
    }
)

add_gold(
    "We are cut off by flood at 108 River Road. No food left for 2 babies, need formula and clean drinking water urgently.",
    "submit_emergency_report",
    {
        "needs": ["food", "water"],
        "need_details": ["formula", "clean drinking water"],
        "hazards": ["flood"],
        "location_text": "108 River Road",
        "affected_people_count": 2,
        "urgency_cue": "urgently",
        "evidence_quotes": ["cut off by flood", "108 River Road", "2 babies", "formula and clean drinking water urgently"]
    }
)

add_gold(
    "Mudflow pushing debris against back wall of Sunnyvale Daycare on Valley Vista Blvd. 15 children and 3 teachers need immediate transport to safety.",
    "submit_emergency_report",
    {
        "needs": ["evacuation", "transport"],
        "need_details": [],
        "hazards": ["landslide"],
        "location_text": "Sunnyvale Daycare on Valley Vista Blvd",
        "affected_people_count": 18,
        "urgency_cue": "immediate transport to safety",
        "evidence_quotes": ["Mudflow pushing debris", "Sunnyvale Daycare on Valley Vista Blvd", "15 children and 3 teachers", "immediate transport to safety"]
    }
)

add_gold(
    "Sinkhole opened right in the middle of Jackson Street intersection. Two sedan tires swallowed, fuel leaking.",
    "submit_emergency_report",
    {
        "needs": [],
        "need_details": [],
        "hazards": ["unsafe_road", "other"],
        "location_text": "Jackson Street intersection",
        "affected_people_count": None,
        "urgency_cue": "Sinkhole opened right in the middle",
        "evidence_quotes": ["Sinkhole opened", "Jackson Street intersection", "fuel leaking"]
    }
)

add_gold(
    "Disabled veteran with oxygen concentrator has lost power at 88 Willow Way. Battery will die in 30 minutes, need emergency battery or transport.",
    "submit_emergency_report",
    {
        "needs": ["supplies", "transport", "medical_help"],
        "need_details": ["emergency battery"],
        "hazards": ["power_outage"],
        "location_text": "88 Willow Way",
        "affected_people_count": 1,
        "urgency_cue": "Battery will die in 30 minutes",
        "evidence_quotes": ["oxygen concentrator has lost power", "88 Willow Way", "Battery will die in 30 minutes", "emergency battery or transport"]
    }
)

add_gold(
    "Bridge on County Road 22 has buckled under fast river current. Steel beams cracked, completely impassable.",
    "submit_emergency_report",
    {
        "needs": [],
        "need_details": [],
        "hazards": ["flood", "unsafe_road", "structural_collapse"],
        "location_text": "County Road 22",
        "affected_people_count": None,
        "urgency_cue": "completely impassable",
        "evidence_quotes": ["Bridge on County Road 22 has buckled", "fast river current", "completely impassable"]
    }
)

# ==============================================================================
# TOOL 2: register_volunteer_offer (18 cases)
# ==============================================================================
add_gold(
    "I am a certified emergency paramedic with full trauma jump kit available to assist anywhere in downtown Springfield today until 10 PM.",
    "register_volunteer_offer",
    {
        "capabilities": ["certified emergency paramedic", "trauma jump kit"],
        "availability_text": "today until 10 PM",
        "location_text": "downtown Springfield",
        "capacity_text": None,
        "constraints": [],
        "evidence_quotes": ["certified emergency paramedic", "trauma jump kit", "downtown Springfield", "today until 10 PM"]
    }
)

add_gold(
    "Offering my 4x4 heavy-duty pickup truck with front winch. Can carry up to 5 people or 1,500 lbs of dry goods. Located in North Hills.",
    "register_volunteer_offer",
    {
        "capabilities": ["4x4 heavy-duty pickup truck with front winch"],
        "availability_text": None,
        "location_text": "North Hills",
        "capacity_text": "up to 5 people or 1,500 lbs of dry goods",
        "constraints": [],
        "evidence_quotes": ["Offering my 4x4 heavy-duty pickup truck with front winch", "North Hills", "up to 5 people or 1,500 lbs of dry goods"]
    }
)

add_gold(
    "We have two 16-foot flat-bottom Jon boats ready for shallow water transport. Staged near Lakefront Marina, can run all night if someone provides fuel.",
    "register_volunteer_offer",
    {
        "capabilities": ["two 16-foot flat-bottom Jon boats for shallow water transport"],
        "availability_text": "all night",
        "location_text": "Lakefront Marina",
        "capacity_text": "two 16-foot flat-bottom Jon boats",
        "constraints": ["if someone provides fuel"],
        "evidence_quotes": ["two 16-foot flat-bottom Jon boats", "Lakefront Marina", "can run all night", "if someone provides fuel"]
    }
)

add_gold(
    "Licensed ham radio operator (callsign K7EMG) equipped with portable HF/VHF go-kit and battery bank. Ready to set up emergency comms relay in East District.",
    "register_volunteer_offer",
    {
        "capabilities": ["Licensed ham radio operator", "portable HF/VHF go-kit", "battery bank"],
        "availability_text": None,
        "location_text": "East District",
        "capacity_text": None,
        "constraints": [],
        "evidence_quotes": ["Licensed ham radio operator", "portable HF/VHF go-kit", "East District", "Ready to set up emergency comms relay"]
    }
)

add_gold(
    "Our church warehouse on 3rd Avenue has dry secure storage space for 10 pallets of donations and commercial kitchen available for food prep.",
    "register_volunteer_offer",
    {
        "capabilities": ["dry secure storage space", "commercial kitchen for food prep"],
        "availability_text": None,
        "location_text": "3rd Avenue",
        "capacity_text": "10 pallets of donations",
        "constraints": [],
        "evidence_quotes": ["church warehouse on 3rd Avenue", "dry secure storage space for 10 pallets", "commercial kitchen available for food prep"]
    }
)

add_gold(
    "I have a Bobcat skid-steer loader and chainsaws for clearing road debris. Based in West End, available sunrise to sunset.",
    "register_volunteer_offer",
    {
        "capabilities": ["Bobcat skid-steer loader", "chainsaws for clearing road debris"],
        "availability_text": "sunrise to sunset",
        "location_text": "West End",
        "capacity_text": None,
        "constraints": [],
        "evidence_quotes": ["Bobcat skid-steer loader and chainsaws", "West End", "available sunrise to sunset"]
    }
)

add_gold(
    "Registered nurse with pediatric experience offering triage help. Stationed near South High School, free for the next 8 hours.",
    "register_volunteer_offer",
    {
        "capabilities": ["Registered nurse with pediatric experience offering triage help"],
        "availability_text": "for the next 8 hours",
        "location_text": "near South High School",
        "capacity_text": None,
        "constraints": [],
        "evidence_quotes": ["Registered nurse with pediatric experience", "near South High School", "free for the next 8 hours"]
    }
)

add_gold(
    "Can donate 50 cases of bottled water and 30 woolen blankets from our local hardware store in Fairview. You must pick them up before 6 PM.",
    "register_volunteer_offer",
    {
        "capabilities": ["bottled water", "woolen blankets"],
        "availability_text": "before 6 PM",
        "location_text": "Fairview",
        "capacity_text": "50 cases of bottled water and 30 woolen blankets",
        "constraints": ["You must pick them up before 6 PM"],
        "evidence_quotes": ["donate 50 cases of bottled water and 30 woolen blankets", "Fairview", "pick them up before 6 PM"]
    }
)

add_gold(
    "Civil engineer here with structural assessment experience. Can inspect damaged houses for re-entry safety in the flood zone this weekend.",
    "register_volunteer_offer",
    {
        "capabilities": ["Civil engineer with structural assessment experience"],
        "availability_text": "this weekend",
        "location_text": "flood zone",
        "capacity_text": None,
        "constraints": [],
        "evidence_quotes": ["Civil engineer here with structural assessment experience", "flood zone", "this weekend"]
    }
)

add_gold(
    "I own a passenger van with wheelchair ramp. Can safely transport 2 wheelchair users and 4 seated passengers in North County area.",
    "register_volunteer_offer",
    {
        "capabilities": ["passenger van with wheelchair ramp"],
        "availability_text": None,
        "location_text": "North County area",
        "capacity_text": "2 wheelchair users and 4 seated passengers",
        "constraints": [],
        "evidence_quotes": ["passenger van with wheelchair ramp", "2 wheelchair users and 4 seated passengers", "North County area"]
    }
)

add_gold(
    "Bilingual fluent Spanish-English speaker willing to do translation and intake at shelters. Available remotely via radio or phone anytime today.",
    "register_volunteer_offer",
    {
        "capabilities": ["Bilingual Spanish-English translation and intake"],
        "availability_text": "anytime today",
        "location_text": None,
        "capacity_text": None,
        "constraints": ["remotely via radio or phone"],
        "evidence_quotes": ["Bilingual fluent Spanish-English speaker willing to do translation", "anytime today", "remotely via radio or phone"]
    }
)

add_gold(
    "I have two 5kW portable gasoline generators with heavy-duty extension cords. Located in Cedar Park, happy to lend them to medical shelters.",
    "register_volunteer_offer",
    {
        "capabilities": ["two 5kW portable gasoline generators with heavy-duty extension cords"],
        "availability_text": None,
        "location_text": "Cedar Park",
        "capacity_text": "two 5kW portable gasoline generators",
        "constraints": ["to medical shelters"],
        "evidence_quotes": ["two 5kW portable gasoline generators", "Cedar Park", "lend them to medical shelters"]
    }
)

add_gold(
    "Veterinarian with emergency pet first aid kits and 10 pet crates. Setting up mobile triage near Fairgrounds, ready now.",
    "register_volunteer_offer",
    {
        "capabilities": ["Veterinarian with emergency pet first aid", "10 pet crates"],
        "availability_text": "ready now",
        "location_text": "near Fairgrounds",
        "capacity_text": "10 pet crates",
        "constraints": [],
        "evidence_quotes": ["Veterinarian with emergency pet first aid", "10 pet crates", "near Fairgrounds", "ready now"]
    }
)

add_gold(
    "Certified search and rescue dog handler with trailing bloodhound. Ready for deployment in mountain sectors upon official dispatch.",
    "register_volunteer_offer",
    {
        "capabilities": ["Certified search and rescue dog handler with trailing bloodhound"],
        "availability_text": None,
        "location_text": "mountain sectors",
        "capacity_text": None,
        "constraints": ["upon official dispatch"],
        "evidence_quotes": ["Certified search and rescue dog handler with trailing bloodhound", "mountain sectors", "upon official dispatch"]
    }
)

add_gold(
    "Can provide 100 hot boxed meals daily from our restaurant downtown starting tomorrow at noon. Transport needed for delivery.",
    "register_volunteer_offer",
    {
        "capabilities": ["hot boxed meals"],
        "availability_text": "starting tomorrow at noon",
        "location_text": "downtown",
        "capacity_text": "100 hot boxed meals daily",
        "constraints": ["Transport needed for delivery"],
        "evidence_quotes": ["100 hot boxed meals daily", "downtown", "starting tomorrow at noon", "Transport needed for delivery"]
    }
)

add_gold(
    "Drone pilot with thermal camera for aerial search over riverbanks. Can operate within 5 miles of Oakridge until battery runs low.",
    "register_volunteer_offer",
    {
        "capabilities": ["Drone pilot with thermal camera for aerial search"],
        "availability_text": "until battery runs low",
        "location_text": "Oakridge",
        "capacity_text": None,
        "constraints": ["within 5 miles of Oakridge"],
        "evidence_quotes": ["Drone pilot with thermal camera", "within 5 miles of Oakridge", "until battery runs low"]
    }
)

add_gold(
    "Electrician offering to check tripped mains and emergency generators for disabled residents in South Park. Free all afternoon.",
    "register_volunteer_offer",
    {
        "capabilities": ["Electrician to check tripped mains and emergency generators"],
        "availability_text": "all afternoon",
        "location_text": "South Park",
        "capacity_text": None,
        "constraints": ["for disabled residents"],
        "evidence_quotes": ["Electrician offering to check tripped mains", "South Park", "Free all afternoon"]
    }
)

add_gold(
    "Local plumbing contractor with two 3-inch trash pumps for dewatering basements. Located on Maple Street, ready on 1 hour notice.",
    "register_volunteer_offer",
    {
        "capabilities": ["plumbing contractor with two 3-inch trash pumps for dewatering"],
        "availability_text": "on 1 hour notice",
        "location_text": "Maple Street",
        "capacity_text": "two 3-inch trash pumps",
        "constraints": [],
        "evidence_quotes": ["two 3-inch trash pumps for dewatering", "Maple Street", "ready on 1 hour notice"]
    }
)

# ==============================================================================
# TOOL 3: submit_field_update (18 cases)
# ==============================================================================
add_gold(
    "Field update: Highway 101 north at mile marker 28 is completely closed due to mudslide across both lanes. Observed 10 minutes ago by patrol unit.",
    "submit_field_update",
    {
        "update_type": "road_status",
        "observation": "Highway 101 north at mile marker 28 closed due to mudslide across both lanes",
        "location_text": "Highway 101 north at mile marker 28",
        "observed_time_text": "10 minutes ago",
        "source_role_text": "patrol unit",
        "evidence_quotes": ["Highway 101 north at mile marker 28", "mudslide across both lanes", "10 minutes ago", "patrol unit"]
    }
)

add_gold(
    "River level gauge at Elm Street bridge has crested at 14.5 feet and is now slowly falling. Noticed just now by local resident.",
    "submit_field_update",
    {
        "update_type": "hazard",
        "observation": "River level gauge at Elm Street bridge crested at 14.5 feet and is slowly falling",
        "location_text": "Elm Street bridge",
        "observed_time_text": "just now",
        "source_role_text": "local resident",
        "evidence_quotes": ["River level gauge at Elm Street bridge", "slowly falling", "just now", "local resident"]
    }
)

add_gold(
    "Memorial High School emergency shelter has reached full capacity. No more cots available as of 2 PM, according to shelter volunteer.",
    "submit_field_update",
    {
        "update_type": "resource_status",
        "observation": "Memorial High School shelter reached full capacity, no cots available",
        "location_text": "Memorial High School",
        "observed_time_text": "as of 2 PM",
        "source_role_text": "shelter volunteer",
        "evidence_quotes": ["Memorial High School", "reached full capacity", "as of 2 PM", "shelter volunteer"]
    }
)

add_gold(
    "Water pressure is dropping rapidly across the West Heights sector. Municipal main appears damaged, reported by utility technician.",
    "submit_field_update",
    {
        "update_type": "infrastructure_status",
        "observation": "Water pressure dropping rapidly across West Heights sector due to damaged municipal main",
        "location_text": "West Heights sector",
        "observed_time_text": None,
        "source_role_text": "utility technician",
        "evidence_quotes": ["Water pressure is dropping rapidly", "West Heights sector", "utility technician"]
    }
)

add_gold(
    "Mandatory evacuation of Zone C is roughly 80% complete. National Guard trucks are assisting remaining residents at Plaza Center.",
    "submit_field_update",
    {
        "update_type": "evacuation_status",
        "observation": "Evacuation of Zone C roughly 80% complete with National Guard trucks assisting",
        "location_text": "Zone C",
        "observed_time_text": None,
        "source_role_text": None,
        "evidence_quotes": ["evacuation of Zone C", "80% complete", "Plaza Center"]
    }
)

add_gold(
    "Downtown business district floodwaters have receded below curb level. Streets are clear of debris as of 30 minutes ago, per city inspector.",
    "submit_field_update",
    {
        "update_type": "area_status",
        "observation": "Downtown floodwaters receded below curb level, streets clear of debris",
        "location_text": "Downtown business district",
        "observed_time_text": "30 minutes ago",
        "source_role_text": "city inspector",
        "evidence_quotes": ["Downtown business district", "floodwaters have receded", "30 minutes ago", "city inspector"]
    }
)

add_gold(
    "Substation on 14th Street suffered a transformer explosion. Black smoke visible, power cut to approximately 3,000 households.",
    "submit_field_update",
    {
        "update_type": "infrastructure_status",
        "observation": "Transformer explosion at 14th Street substation cut power to ~3,000 households",
        "location_text": "Substation on 14th Street",
        "observed_time_text": None,
        "source_role_text": None,
        "evidence_quotes": ["Substation on 14th Street", "transformer explosion", "power cut"]
    }
)

add_gold(
    "Cellular tower in Oakridge has switched to backup battery power. Signal is weak throughout the neighborhood.",
    "submit_field_update",
    {
        "update_type": "infrastructure_status",
        "observation": "Cellular tower in Oakridge running on backup battery with weak signal",
        "location_text": "Oakridge",
        "observed_time_text": None,
        "source_role_text": None,
        "evidence_quotes": ["Cellular tower in Oakridge", "backup battery power", "Signal is weak"]
    }
)

add_gold(
    "Downed live high-voltage wires across both lanes of Pinecrest Boulevard near the railroad crossing. Fire crew on scene directing traffic.",
    "submit_field_update",
    {
        "update_type": "hazard",
        "observation": "Downed live high-voltage wires across Pinecrest Boulevard near railroad crossing",
        "location_text": "Pinecrest Boulevard near the railroad crossing",
        "observed_time_text": None,
        "source_role_text": "Fire crew on scene",
        "evidence_quotes": ["Downed live high-voltage wires", "Pinecrest Boulevard near the railroad crossing", "Fire crew on scene"]
    }
)

add_gold(
    "Harbor bridge south span reopened to single-lane emergency vehicles only. Civilian traffic still barred, noted by transit supervisor 5 mins ago.",
    "submit_field_update",
    {
        "update_type": "road_status",
        "observation": "Harbor bridge south span reopened for single-lane emergency vehicles only",
        "location_text": "Harbor bridge south span",
        "observed_time_text": "5 mins ago",
        "source_role_text": "transit supervisor",
        "evidence_quotes": ["Harbor bridge south span", "emergency vehicles only", "5 mins ago", "transit supervisor"]
    }
)

add_gold(
    "Bottled water distribution point at Central Park is out of stock. New shipment expected around 5 PM, announced by volunteer coordinator.",
    "submit_field_update",
    {
        "update_type": "resource_status",
        "observation": "Water distribution at Central Park out of stock, next shipment around 5 PM",
        "location_text": "Central Park",
        "observed_time_text": None,
        "source_role_text": "volunteer coordinator",
        "evidence_quotes": ["Bottled water distribution point at Central Park", "out of stock", "volunteer coordinator"]
    }
)

add_gold(
    "Air quality index in South Valley has reached hazardous level 310 due to shifting smoke plume. Resident observation just now.",
    "submit_field_update",
    {
        "update_type": "hazard",
        "observation": "Air quality in South Valley reached hazardous level 310 from shifting smoke plume",
        "location_text": "South Valley",
        "observed_time_text": "just now",
        "source_role_text": "Resident observation",
        "evidence_quotes": ["Air quality index in South Valley", "hazardous level 310", "just now", "Resident observation"]
    }
)

add_gold(
    "Railroad underpass on 9th Avenue is submerged in 4 feet of standing water. Small sedan tried to cross and stalled.",
    "submit_field_update",
    {
        "update_type": "road_status",
        "observation": "Railroad underpass on 9th Avenue flooded with 4 feet of standing water, vehicle stalled",
        "location_text": "Railroad underpass on 9th Avenue",
        "observed_time_text": None,
        "source_role_text": None,
        "evidence_quotes": ["Railroad underpass on 9th Avenue", "submerged in 4 feet of standing water", "stalled"]
    }
)

add_gold(
    "Field hospital setup at County Fairgrounds Arena is operational and receiving minor injuries as of 1 hour ago.",
    "submit_field_update",
    {
        "update_type": "resource_status",
        "observation": "Field hospital at County Fairgrounds Arena operational and receiving minor injuries",
        "location_text": "County Fairgrounds Arena",
        "observed_time_text": "1 hour ago",
        "source_role_text": None,
        "evidence_quotes": ["Field hospital setup at County Fairgrounds Arena", "operational", "1 hour ago"]
    }
)

add_gold(
    "Gas station at Exit 42 on Interstate 80 has run out of diesel and unleaded gasoline. Station manager confirmed 15 minutes ago.",
    "submit_field_update",
    {
        "update_type": "resource_status",
        "observation": "Exit 42 gas station out of diesel and unleaded gasoline",
        "location_text": "Exit 42 on Interstate 80",
        "observed_time_text": "15 minutes ago",
        "source_role_text": "Station manager",
        "evidence_quotes": ["Exit 42 on Interstate 80", "run out of diesel and unleaded gasoline", "15 minutes ago", "Station manager"]
    }
)

add_gold(
    "Debris clearance along Old Mill Road finished by public works crews. Both lanes are now open without restrictions.",
    "submit_field_update",
    {
        "update_type": "road_status",
        "observation": "Debris clearance on Old Mill Road completed, both lanes open without restrictions",
        "location_text": "Old Mill Road",
        "observed_time_text": None,
        "source_role_text": "public works crews",
        "evidence_quotes": ["Old Mill Road", "public works crews", "Both lanes are now open"]
    }
)

add_gold(
    "Wildfire containment line along North Ridge held through the night. No structural loss in sector 4, reported by incident commander.",
    "submit_field_update",
    {
        "update_type": "hazard",
        "observation": "Wildfire containment line along North Ridge held, no structural loss in sector 4",
        "location_text": "North Ridge",
        "observed_time_text": None,
        "source_role_text": "incident commander",
        "evidence_quotes": ["Wildfire containment line along North Ridge held", "No structural loss in sector 4", "incident commander"]
    }
)

add_gold(
    "Boil water advisory issued for all residents on South Hill due to water main depressurization. City health department bulletin.",
    "submit_field_update",
    {
        "update_type": "infrastructure_status",
        "observation": "Boil water advisory issued for South Hill residents from water main depressurization",
        "location_text": "South Hill",
        "observed_time_text": None,
        "source_role_text": "City health department",
        "evidence_quotes": ["Boil water advisory", "South Hill", "City health department"]
    }
)

# ==============================================================================
# TOOL 4: request_assistance (24 cases: 8 safe_route, 8 shelter, 8 local_updates)
# ==============================================================================
# Safe route (8 cases)
add_gold(
    "Is there a safe route to drive from Riverdale Community Center to St. Peter Hospital avoiding flood waters and closed roads?",
    "request_assistance",
    {
        "request_type": "safe_route",
        "origin_text": "Riverdale Community Center",
        "destination_text": "St. Peter Hospital",
        "near_location_text": None,
        "avoid_hazards": ["flood", "unsafe_road"],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["safe route to drive", "Riverdale Community Center", "St. Peter Hospital", "avoiding flood waters and closed roads"]
    }
)

add_gold(
    "Need driving navigation from 44 Elm Street to North County Evacuation Point. We have 4 people including 1 in a wheelchair so we need wheelchair accessible path.",
    "request_assistance",
    {
        "request_type": "safe_route",
        "origin_text": "44 Elm Street",
        "destination_text": "North County Evacuation Point",
        "near_location_text": None,
        "avoid_hazards": [],
        "people_count": 4,
        "accessibility_needs": ["wheelchair accessible path"],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["driving navigation", "44 Elm Street", "North County Evacuation Point", "4 people", "wheelchair accessible path"]
    }
)

add_gold(
    "Can you give me walking directions from Metro Plaza to Westside Shelter avoiding active fire zones and smoke plumes?",
    "request_assistance",
    {
        "request_type": "safe_route",
        "origin_text": "Metro Plaza",
        "destination_text": "Westside Shelter",
        "near_location_text": None,
        "avoid_hazards": ["fire"],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["walking directions", "Metro Plaza", "Westside Shelter", "avoiding active fire zones"]
    }
)

add_gold(
    "Which bridge is open right now to cross from South Bank to Downtown? Need to avoid downed power lines.",
    "request_assistance",
    {
        "request_type": "safe_route",
        "origin_text": "South Bank",
        "destination_text": "Downtown",
        "near_location_text": None,
        "avoid_hazards": ["power_outage"],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["cross from South Bank to Downtown", "avoid downed power lines"]
    }
)

add_gold(
    "We need route guidance from Valley View Road out towards Interstate 80 East. Please avoid any landslides or debris.",
    "request_assistance",
    {
        "request_type": "safe_route",
        "origin_text": "Valley View Road",
        "destination_text": "Interstate 80 East",
        "near_location_text": None,
        "avoid_hazards": ["landslide"],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["route guidance", "Valley View Road", "Interstate 80 East", "avoid any landslides"]
    }
)

add_gold(
    "Looking for a safe route for 6 people from Greenfield Middle School to the airport evacuation terminal.",
    "request_assistance",
    {
        "request_type": "safe_route",
        "origin_text": "Greenfield Middle School",
        "destination_text": "airport evacuation terminal",
        "near_location_text": None,
        "avoid_hazards": [],
        "people_count": 6,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["safe route", "6 people", "Greenfield Middle School", "airport evacuation terminal"]
    }
)

add_gold(
    "How can I drive from Harbor Point to Oakridge avoiding chemical spills and flooded underpasses?",
    "request_assistance",
    {
        "request_type": "safe_route",
        "origin_text": "Harbor Point",
        "destination_text": "Oakridge",
        "near_location_text": None,
        "avoid_hazards": ["chemical_hazard", "flood"],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["drive from Harbor Point to Oakridge", "avoiding chemical spills", "flooded underpasses"]
    }
)

add_gold(
    "Looking for passable route for an ambulance from Mercy Station to Hillside Care Home avoiding blocked streets.",
    "request_assistance",
    {
        "request_type": "safe_route",
        "origin_text": "Mercy Station",
        "destination_text": "Hillside Care Home",
        "near_location_text": None,
        "avoid_hazards": ["unsafe_road"],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["passable route for an ambulance", "Mercy Station", "Hillside Care Home", "avoiding blocked streets"]
    }
)

# Shelter (8 cases)
add_gold(
    "Where is the closest open emergency shelter near Maplewood? We are a family of 5 with 2 dogs so we need pet-friendly shelter.",
    "request_assistance",
    {
        "request_type": "shelter",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Maplewood",
        "avoid_hazards": [],
        "people_count": 5,
        "accessibility_needs": [],
        "other_requirements": ["pet-friendly shelter"],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["closest open emergency shelter", "Maplewood", "family of 5", "pet-friendly shelter"]
    }
)

add_gold(
    "Need shelter near downtown for 2 adults. Must have step-free wheelchair access for my elderly father.",
    "request_assistance",
    {
        "request_type": "shelter",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "downtown",
        "avoid_hazards": [],
        "people_count": 2,
        "accessibility_needs": ["step-free wheelchair access"],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["Need shelter", "downtown", "2 adults", "step-free wheelchair access"]
    }
)

add_gold(
    "Are there any evacuation centers open in East Springfield with available cots for 3 people tonight?",
    "request_assistance",
    {
        "request_type": "shelter",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "East Springfield",
        "avoid_hazards": [],
        "people_count": 3,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["evacuation centers open", "East Springfield", "3 people"]
    }
)

add_gold(
    "Looking for an emergency shelter near Highland Park that accepts cats and small animals.",
    "request_assistance",
    {
        "request_type": "shelter",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Highland Park",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": ["accepts cats and small animals"],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["emergency shelter", "Highland Park", "accepts cats and small animals"]
    }
)

add_gold(
    "Where can 8 family members find refuge near West End Community Center with electricity for medical devices?",
    "request_assistance",
    {
        "request_type": "shelter",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "West End Community Center",
        "avoid_hazards": [],
        "people_count": 8,
        "accessibility_needs": [],
        "other_requirements": ["electricity for medical devices"],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["find refuge", "8 family members", "West End Community Center", "electricity for medical devices"]
    }
)

add_gold(
    "Is the shelter at Pinecrest Civic Center currently open and taking walk-ins?",
    "request_assistance",
    {
        "request_type": "shelter",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Pinecrest Civic Center",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["shelter", "Pinecrest Civic Center", "taking walk-ins"]
    }
)

add_gold(
    "Need urgent overnight shelter for 1 individual with sensory sensitivity room near Fairview.",
    "request_assistance",
    {
        "request_type": "shelter",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Fairview",
        "avoid_hazards": [],
        "people_count": 1,
        "accessibility_needs": [],
        "other_requirements": ["sensory sensitivity room"],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["overnight shelter", "1 individual", "sensory sensitivity room", "Fairview"]
    }
)

add_gold(
    "Can you find nearest safe dry shelter around North Harbor for 4 people escaping coastal surge?",
    "request_assistance",
    {
        "request_type": "shelter",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "North Harbor",
        "avoid_hazards": [],
        "people_count": 4,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": [],
        "since_text": None,
        "evidence_quotes": ["safe dry shelter", "North Harbor", "4 people"]
    }
)

# Local updates (8 cases)
add_gold(
    "What are the latest updates on road closures and flood hazards around Riverdale since 8 AM?",
    "request_assistance",
    {
        "request_type": "local_updates",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Riverdale",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": ["road_status", "hazard"],
        "since_text": "since 8 AM",
        "evidence_quotes": ["latest updates", "road closures", "flood hazards", "Riverdale", "since 8 AM"]
    }
)

add_gold(
    "Give me local situational updates on evacuation status and shelter availability near Oakridge from the last 2 hours.",
    "request_assistance",
    {
        "request_type": "local_updates",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Oakridge",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": ["evacuation_status", "shelter"],
        "since_text": "last 2 hours",
        "evidence_quotes": ["local situational updates", "evacuation status", "shelter availability", "Oakridge", "last 2 hours"]
    }
)

add_gold(
    "Has power been restored in Greenfield? Need updates on power outage and water infrastructure.",
    "request_assistance",
    {
        "request_type": "local_updates",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Greenfield",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": ["infrastructure_status"],
        "since_text": None,
        "evidence_quotes": ["updates on power outage", "water infrastructure", "Greenfield"]
    }
)

add_gold(
    "What is the status of active wildfire containment near Pine Ridge reported since yesterday?",
    "request_assistance",
    {
        "request_type": "local_updates",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Pine Ridge",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": ["hazard"],
        "since_text": "since yesterday",
        "evidence_quotes": ["status of active wildfire containment", "Pine Ridge", "since yesterday"]
    }
)

add_gold(
    "Any reports on food and clean water supply distribution in South Sector this afternoon?",
    "request_assistance",
    {
        "request_type": "local_updates",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "South Sector",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": ["resource_status"],
        "since_text": "this afternoon",
        "evidence_quotes": ["food and clean water supply distribution", "South Sector", "this afternoon"]
    }
)

add_gold(
    "Check field reports for bridge safety and highway passability near Cedar Junction.",
    "request_assistance",
    {
        "request_type": "local_updates",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Cedar Junction",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": ["road_status", "infrastructure_status"],
        "since_text": None,
        "evidence_quotes": ["bridge safety", "highway passability", "Cedar Junction"]
    }
)

add_gold(
    "Can you provide all rescue operation and hazard bulletins around Metro Station since noon?",
    "request_assistance",
    {
        "request_type": "local_updates",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "Metro Station",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": ["rescue", "hazard"],
        "since_text": "since noon",
        "evidence_quotes": ["rescue operation and hazard bulletins", "Metro Station", "since noon"]
    }
)

add_gold(
    "What is the current area status for East District regarding curfew and emergency orders?",
    "request_assistance",
    {
        "request_type": "local_updates",
        "origin_text": None,
        "destination_text": None,
        "near_location_text": "East District",
        "avoid_hazards": [],
        "people_count": None,
        "accessibility_needs": [],
        "other_requirements": [],
        "topics": ["area_status"],
        "since_text": None,
        "evidence_quotes": ["current area status", "East District", "curfew and emergency orders"]
    }
)

# ==============================================================================
# TOOL 5: request_report_closure (16 cases)
# ==============================================================================
add_gold(
    "Please close emergency report #R-4091. All residents have been safely evacuated and water has receded. Confirmed by Battalion Chief Miller.",
    "request_report_closure",
    {
        "report_id": "R-4091",
        "requested_status": "resolved",
        "reason_text": "All residents have been safely evacuated and water has receded",
        "confirmation_source_text": "Battalion Chief Miller",
        "evidence_quotes": ["close emergency report", "R-4091", "All residents have been safely evacuated", "Battalion Chief Miller"]
    }
)

add_gold(
    "Cancel incident report #E-1082 immediately. It was a duplicate report called in by a neighbor by mistake. Caller confirmed false alarm.",
    "request_report_closure",
    {
        "report_id": "E-1082",
        "requested_status": "cancelled",
        "reason_text": "duplicate report called in by a neighbor by mistake",
        "confirmation_source_text": "Caller confirmed false alarm",
        "evidence_quotes": ["Cancel incident report", "E-1082", "duplicate report", "Caller confirmed false alarm"]
    }
)

add_gold(
    "Our request for boat rescue at 104 Riverside Drive is resolved. A volunteer boat picked up my family 20 minutes ago. Homeowner speaking.",
    "request_report_closure",
    {
        "report_id": None,
        "requested_status": "resolved",
        "reason_text": "volunteer boat picked up my family 20 minutes ago",
        "confirmation_source_text": "Homeowner speaking",
        "evidence_quotes": ["request for boat rescue", "resolved", "volunteer boat picked up my family", "Homeowner speaking"]
    }
)

add_gold(
    "Mark ticket #8843 as resolved. The tree blocking Elm Street has been cleared by municipal crew and traffic is flowing.",
    "request_report_closure",
    {
        "report_id": "8843",
        "requested_status": "resolved",
        "reason_text": "tree blocking Elm Street has been cleared by municipal crew",
        "confirmation_source_text": "municipal crew",
        "evidence_quotes": ["Mark ticket", "8843", "resolved", "cleared by municipal crew"]
    }
)

add_gold(
    "Please cancel the ambulance dispatch request for 72 Pine Road. Patient's condition stabilized and family is driving them to clinic.",
    "request_report_closure",
    {
        "report_id": None,
        "requested_status": "cancelled",
        "reason_text": "Patient's condition stabilized and family is driving them to clinic",
        "confirmation_source_text": None,
        "evidence_quotes": ["cancel the ambulance dispatch request", "72 Pine Road", "Patient's condition stabilized"]
    }
)

add_gold(
    "Resolve report #CS-9912. Power company restored electricity to the nursing home and emergency backup generator shut down. Confirmed by facility manager.",
    "request_report_closure",
    {
        "report_id": "CS-9912",
        "requested_status": "resolved",
        "reason_text": "Power company restored electricity to the nursing home",
        "confirmation_source_text": "facility manager",
        "evidence_quotes": ["Resolve report", "CS-9912", "restored electricity", "facility manager"]
    }
)

add_gold(
    "Report #5520 can be closed now. Fire has been fully extinguished by local brigade, no smoldering embers remaining. Stated by Captain Reynolds.",
    "request_report_closure",
    {
        "report_id": "5520",
        "requested_status": "resolved",
        "reason_text": "Fire has been fully extinguished by local brigade",
        "confirmation_source_text": "Captain Reynolds",
        "evidence_quotes": ["Report #5520 can be closed", "Fire has been fully extinguished", "Captain Reynolds"]
    }
)

add_gold(
    "Cancel report #7714 regarding gas leak at 334 Oak Ave. Gas technician investigated and found zero ppm reading, faulty pilot light fixed.",
    "request_report_closure",
    {
        "report_id": "7714",
        "requested_status": "cancelled",
        "reason_text": "Gas technician investigated and found zero ppm reading, faulty pilot light fixed",
        "confirmation_source_text": "Gas technician",
        "evidence_quotes": ["Cancel report", "7714", "found zero ppm reading", "Gas technician"]
    }
)

add_gold(
    "Request closure for emergency ticket #9021. Stranded hikers at North Bluff made contact and hiked down safely on their own.",
    "request_report_closure",
    {
        "report_id": "9021",
        "requested_status": "resolved",
        "reason_text": "Stranded hikers at North Bluff made contact and hiked down safely on their own",
        "confirmation_source_text": None,
        "evidence_quotes": ["Request closure for emergency ticket", "9021", "hiked down safely on their own"]
    }
)

add_gold(
    "We want to cancel the missing cat report from yesterday. Pet returned home unharmed.",
    "request_report_closure",
    {
        "report_id": None,
        "requested_status": "cancelled",
        "reason_text": "Pet returned home unharmed",
        "confirmation_source_text": None,
        "evidence_quotes": ["cancel the missing cat report", "Pet returned home unharmed"]
    }
)

add_gold(
    "Mark case #3319 as resolved. Drinking water tanker arrived and filled neighborhood cistern. Verified by community elder.",
    "request_report_closure",
    {
        "report_id": "3319",
        "requested_status": "resolved",
        "reason_text": "Drinking water tanker arrived and filled neighborhood cistern",
        "confirmation_source_text": "community elder",
        "evidence_quotes": ["Mark case", "3319", "resolved", "tanker arrived", "community elder"]
    }
)

add_gold(
    "Cancel alert ticket #9440. High water warning was entered for wrong bridge due to clerical error. Dispatched by Operator 12.",
    "request_report_closure",
    {
        "report_id": "9440",
        "requested_status": "cancelled",
        "reason_text": "High water warning was entered for wrong bridge due to clerical error",
        "confirmation_source_text": "Operator 12",
        "evidence_quotes": ["Cancel alert ticket", "9440", "clerical error", "Operator 12"]
    }
)

add_gold(
    "Report #1128 closure request: Evacuees at Lakeview church have all been relocated to county shelter. Confirmed by Red Cross liaison.",
    "request_report_closure",
    {
        "report_id": "1128",
        "requested_status": "resolved",
        "reason_text": "Evacuees at Lakeview church have all been relocated to county shelter",
        "confirmation_source_text": "Red Cross liaison",
        "evidence_quotes": ["Report #1128 closure request", "relocated to county shelter", "Red Cross liaison"]
    }
)

add_gold(
    "Cancel field emergency report #6601. Accidental panic button press by security guard during shift change. Guard confirmed no danger.",
    "request_report_closure",
    {
        "report_id": "6601",
        "requested_status": "cancelled",
        "reason_text": "Accidental panic button press by security guard during shift change",
        "confirmation_source_text": "Guard confirmed no danger",
        "evidence_quotes": ["Cancel field emergency report", "6601", "Accidental panic button press", "Guard confirmed no danger"]
    }
)

add_gold(
    "Close ticket #5182. Road wash-out on Old Mill Lane was patched with gravel and culvert cleared. Verified by DPW foreman.",
    "request_report_closure",
    {
        "report_id": "5182",
        "requested_status": "resolved",
        "reason_text": "Road wash-out on Old Mill Lane was patched with gravel and culvert cleared",
        "confirmation_source_text": "DPW foreman",
        "evidence_quotes": ["Close ticket", "5182", "patched with gravel", "DPW foreman"]
    }
)

add_gold(
    "Please cancel request #7109 for sandbag delivery. Neighboring property lent us extra sandbags and barrier is complete.",
    "request_report_closure",
    {
        "report_id": "7109",
        "requested_status": "cancelled",
        "reason_text": "Neighboring property lent us extra sandbags and barrier is complete",
        "confirmation_source_text": None,
        "evidence_quotes": ["cancel request", "7109", "barrier is complete"]
    }
)

# ==============================================================================
# NO-CALL / AMBIGUOUS / IRRELEVANT (18 cases)
# ==============================================================================
add_gold(
    "Good morning team, checking if the Monday morning briefing is still scheduled on Zoom.",
    None,
    {}
)

add_gold(
    "EXERCISE EXERCISE EXERCISE: This is a simulated disaster drill for county communications. Do not take operational action.",
    None,
    {}
)

add_gold(
    "Did anyone watch the documentary on the 1927 Mississippi River flood last night on PBS?",
    None,
    {}
)

add_gold(
    "What is the recommended ratio of bleach to water when purifying contaminated river water for long-term storage?",
    None,
    {}
)

add_gold(
    "I heard a rumor on Facebook that the dam up north collapsed, but city officials just posted that it is completely false.",
    None,
    {}
)

add_gold(
    "If a Category 5 hurricane were to hit our coastline, how high would storm surge barriers need to be?",
    None,
    {}
)

add_gold(
    "Can anyone recommend a good waterproof jacket and hiking boots brand for rainy weather?",
    None,
    {}
)

add_gold(
    "The recipe says to boil potatoes for 20 minutes before mashing. Does anyone add garlic powder?",
    None,
    {}
)

add_gold(
    "REMINDER: Annual staff picnic will take place this Saturday at Riverside Park unless rain is forecast.",
    None,
    {}
)

add_gold(
    "Thank you to all first responders who worked tireless shifts during last month's snowstorm. We appreciate you!",
    None,
    {}
)

add_gold(
    "My phone battery was low earlier today, but I charged it back up to 90% now.",
    None,
    {}
)

add_gold(
    "Quoting the mayor's speech: 'Our community resilience will overcome any future weather challenge.'",
    None,
    {}
)

add_gold(
    "Is it true that modern building codes in California require earthquake shock absorbers under skyscraper foundations?",
    None,
    {}
)

add_gold(
    "Just testing my satellite messenger text function. 1, 2, 3, test test.",
    None,
    {}
)

add_gold(
    "Looking forward to the weekend football game between Central High and West High.",
    None,
    {}
)

add_gold(
    "Can someone explain the legal difference between a flood watch and a flood warning under NOAA guidelines?",
    None,
    {}
)

add_gold(
    "My dog barked at a raccoon in the backyard around 2 AM, but the raccoon ran away into the woods.",
    None,
    {}
)

add_gold(
    "FYI: The library book sale has been extended through Wednesday afternoon in the main lobby.",
    None,
    {}
)

# Output file
OUTPUT_FILE = SCRIPT_DIR / "datasets" / "gold_test.jsonl"
with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
    for case in GOLD_CASES:
        f.write(json.dumps(case, ensure_ascii=False) + "\n")

print(f"Successfully generated {len(GOLD_CASES)} gold test cases in {OUTPUT_FILE}")
