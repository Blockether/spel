## DELETE http://localhost:34697/echo → 200 OK

### Request Headers
```
DELETE http://localhost:34697/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 29 Mar 2026 20:52:01 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:34697/echo' \
  -X DELETE
```
