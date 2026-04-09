## PATCH http://localhost:44095/echo → 200 OK

### Request Headers
```
PATCH http://localhost:44095/echo
```

### Request Body
```json
{
  "email": "alice3@example.org"
}
```

### Response Headers
```
content-length: 71
content-type: application/json
date: Thu, 09 Apr 2026 19:01:56 GMT
```

### Response Body
```json
{
  "method": "PATCH",
  "path": "/echo",
  "body": {
    "email": "alice3@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:44095/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
