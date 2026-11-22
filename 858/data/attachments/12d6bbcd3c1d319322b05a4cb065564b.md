## DELETE http://localhost:44623/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 22 Nov 2026 19:19:31 GMT
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
curl 'http://localhost:44623/echo' \
  -X DELETE
```
