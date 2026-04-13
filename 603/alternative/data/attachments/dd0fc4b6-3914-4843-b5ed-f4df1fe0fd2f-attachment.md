## DELETE http://localhost:39925/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 13 Apr 2026 13:00:49 GMT
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
curl 'http://localhost:39925/echo' \
  -X DELETE
```
