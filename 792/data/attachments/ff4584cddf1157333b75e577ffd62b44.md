## PATCH http://localhost:38099/echo → 200 OK

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
date: Tue, 20 Oct 2026 19:58:18 GMT
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
curl 'http://localhost:38099/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
