## PATCH http://localhost:44649/echo → 200 OK

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
date: Mon, 27 Jul 2026 12:08:10 GMT
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
curl 'http://localhost:44649/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
