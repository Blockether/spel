## PUT http://localhost:41789/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Wed, 15 Apr 2026 10:32:52 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:41789/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
