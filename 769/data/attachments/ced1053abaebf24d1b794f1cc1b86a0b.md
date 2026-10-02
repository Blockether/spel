## PATCH http://localhost:45667/echo → 200 OK

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
date: Fri, 02 Oct 2026 15:50:46 GMT
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
curl 'http://localhost:45667/echo' \
  -X PATCH \
  -d '{
  "email": "alice3@example.org"
}'
```
